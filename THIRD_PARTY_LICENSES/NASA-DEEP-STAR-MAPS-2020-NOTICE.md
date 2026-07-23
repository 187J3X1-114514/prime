# NASA Deep Star Maps 2020 notice

Prime includes a losslessly repacked copy of `starmap_2020_8k.exr` from
[NASA SVS Deep Star Maps 2020](https://svs.gsfc.nasa.gov/4851/). The source
image is the 8192×4096 plate carrée celestial map in ICRF/J2000 coordinates,
centered at 0h right ascension with right ascension increasing to the left.
Prime preserves the source RGB OpenEXR HALF values and adds an opaque alpha
channel for direct GPU upload. A derived low-resolution importance table is
used only to reduce path-tracing variance.

Source SHA-256:
`DC6C4F413E85707A29A25A9451148154554ECCA2C996F84FA8F47B65EF9FF7C4`

Please give credit for this item to:

NASA/Goddard Space Flight Center Scientific Visualization Studio. Gaia DR2:
[ESA/Gaia/DPAC](https://gea.esac.esa.int/archive/documentation/GDR2/Miscellaneous/sec_credit_and_citation_instructions/).
Constellation figures based on those developed for the IAU by Alan MacRobert
of *Sky and Telescope* magazine (Roger Sinnott and Rick Fienberg).
