package dev.prime.render.terrain;

/** Immutable Walker alias table with the exact probability mass induced by its f32 thresholds. */
final class PowerAliasTable {
    private final float[] aliasProbabilities;
    private final int[] aliases;
    private final float[] probabilityMasses;

    private PowerAliasTable(
            float[] aliasProbabilities,
            int[] aliases,
            float[] probabilityMasses) {
        this.aliasProbabilities = aliasProbabilities;
        this.aliases = aliases;
        this.probabilityMasses = probabilityMasses;
    }

    static PowerAliasTable build(float[] weights) {
        if (weights.length == 0) {
            throw new IllegalArgumentException("An alias table requires at least one weight");
        }
        int count = weights.length;
        double sum = 0.0;
        for (float weight : weights) {
            if (!(weight > 0.0F) || !Float.isFinite(weight)) {
                throw new IllegalArgumentException(
                        "Alias weights must be finite and positive");
            }
            sum += weight;
        }
        if (!(sum > 0.0) || !Double.isFinite(sum)) {
            throw new IllegalArgumentException("Alias weight sum must be finite and positive");
        }

        float[] probabilities = new float[count];
        int[] aliases = new int[count];
        double[] scaled = new double[count];
        int[] small = new int[count];
        int[] large = new int[count];
        int smallSize = 0;
        int largeSize = 0;
        for (int index = 0; index < count; index++) {
            scaled[index] = weights[index] * count / sum;
            if (scaled[index] < 1.0) {
                small[smallSize++] = index;
            } else {
                large[largeSize++] = index;
            }
        }
        while (smallSize != 0 && largeSize != 0) {
            int smallIndex = small[--smallSize];
            int largeIndex = large[--largeSize];
            probabilities[smallIndex] = (float) scaled[smallIndex];
            aliases[smallIndex] = largeIndex;
            scaled[largeIndex] += scaled[smallIndex] - 1.0;
            if (scaled[largeIndex] < 1.0) {
                small[smallSize++] = largeIndex;
            } else {
                large[largeSize++] = largeIndex;
            }
        }
        while (largeSize != 0) {
            int index = large[--largeSize];
            probabilities[index] = 1.0F;
            aliases[index] = index;
        }
        while (smallSize != 0) {
            int index = small[--smallSize];
            probabilities[index] = 1.0F;
            aliases[index] = index;
        }

        // Store the distribution actually represented by the f32 thresholds. RIS must divide by
        // this proposal, not by the ideal double-precision weights used to construct the table.
        double[] exactMasses = new double[count];
        double inverseCount = 1.0 / count;
        for (int bucket = 0; bucket < count; bucket++) {
            double probability = probabilities[bucket];
            exactMasses[bucket] += probability * inverseCount;
            exactMasses[aliases[bucket]] += (1.0 - probability) * inverseCount;
        }
        float[] masses = new float[count];
        for (int index = 0; index < count; index++) {
            masses[index] = (float) exactMasses[index];
            if (!(masses[index] > 0.0F) || !Float.isFinite(masses[index])) {
                throw new IllegalStateException("Alias table lost positive support");
            }
        }
        return new PowerAliasTable(probabilities, aliases, masses);
    }

    int size() {
        return this.aliases.length;
    }

    float aliasProbability(int index) {
        return this.aliasProbabilities[index];
    }

    int alias(int index) {
        return this.aliases[index];
    }

    float probabilityMass(int index) {
        return this.probabilityMasses[index];
    }

    int[] pack() {
        int[] result = new int[Math.multiplyExact(this.size(), 2)];
        packInto(result, 0);
        return result;
    }

    void packInto(int[] target, int wordOffset) {
        int end = Math.addExact(wordOffset, Math.multiplyExact(this.size(), 2));
        if (wordOffset < 0 || end > target.length) {
            throw new IndexOutOfBoundsException(wordOffset);
        }
        int cursor = wordOffset;
        for (int index = 0; index < this.size(); index++) {
            target[cursor++] = Float.floatToRawIntBits(this.aliasProbabilities[index]);
            target[cursor++] = this.aliases[index];
        }
    }
}
