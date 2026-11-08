package local.movementtrackerforvam.movementtrackerforvam;

/** Keep roughly the former 640x360 pixel budget while matching the video viewport. */
final class CameraStreamSize {
    static int[] forViewport(int width, int height) {
        if (width <= 0 || height <= 0) return new int[]{360, 640};
        double aspect = Math.max(.35, Math.min(1.0, width / (double) height));
        int w = Math.max(2, (int)Math.round(Math.sqrt(230400 * aspect) / 2) * 2);
        int h = Math.max(2, (int)Math.round(w / aspect / 2) * 2);
        return new int[]{w, h};
    }
}
