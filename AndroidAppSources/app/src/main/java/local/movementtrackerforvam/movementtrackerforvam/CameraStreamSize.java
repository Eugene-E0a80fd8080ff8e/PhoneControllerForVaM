package local.movementtrackerforvam.movementtrackerforvam;

/** Match the portrait preview using the pixel budget of a 16:9 resolution. */
final class CameraStreamSize {
    static final int DEFAULT_RESOLUTION = 360;

    static int[] forViewport(int width, int height, int resolution) {
        switch (resolution) {
            case 240:
            case 360:
            case 480:
            case 560:
            case 640:
            case 720:
                break;
            default:
                resolution = DEFAULT_RESOLUTION;
        }

        double aspect = width <= 0 || height <= 0
                ? 9.0 / 16.0
                : Math.max(.35, Math.min(1.0, width / (double) height));
        double pixelBudget = resolution * resolution * (16.0 / 9.0);
        int streamWidth = (int) Math.round(Math.sqrt(pixelBudget * aspect) / 2) * 2;
        int streamHeight = (int) Math.round(streamWidth / aspect / 2) * 2;
        return new int[]{streamWidth, streamHeight};
    }
}
