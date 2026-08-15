package dev.prime.render.vulkan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable ray-generation module, group, and shader-record contract. */
final class RaygenSchedule {
    record Group(int module, int control) {}

    private final List<String> modules;
    private final List<Group> groups;

    RaygenSchedule(List<String> modules, List<Group> groups) {
        this.modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
        this.groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
        if (this.modules.isEmpty() || this.groups.isEmpty()) {
            throw new IllegalArgumentException("Raygen schedule cannot be empty");
        }
        for (String module : this.modules) {
            if (module == null || module.isBlank()) {
                throw new IllegalArgumentException("Raygen module resource cannot be blank");
            }
        }
        for (Group group : this.groups) {
            if (group.module() < 0 || group.module() >= this.modules.size()) {
                throw new IllegalArgumentException("Raygen group references an invalid module");
            }
        }
    }

    static RaygenSchedule of(List<String> modules, int[] groupModules, int[] controls) {
        Objects.requireNonNull(groupModules, "groupModules");
        Objects.requireNonNull(controls, "controls");
        if (groupModules.length != controls.length) {
            throw new IllegalArgumentException("Raygen group metadata length mismatch");
        }
        List<Group> groups = new ArrayList<>(groupModules.length);
        for (int index = 0; index < groupModules.length; index++) {
            groups.add(new Group(groupModules[index], controls[index]));
        }
        return new RaygenSchedule(modules, groups);
    }

    static RaygenSchedule single(String module, int control) {
        return new RaygenSchedule(List.of(module), List.of(new Group(0, control)));
    }

    int moduleCount() {
        return this.modules.size();
    }

    int groupCount() {
        return this.groups.size();
    }

    String moduleResource(int module) {
        return this.modules.get(module);
    }

    int module(int group) {
        return this.groups.get(group).module();
    }

    int control(int group) {
        return this.groups.get(group).control();
    }
}
