#language slang 2026
module "entry/offline/camera_trace.raygeneration.slang";

import "phase/offline/camera_trace.slang";

[shader("raygeneration")]
void main() { primePhase(); }
