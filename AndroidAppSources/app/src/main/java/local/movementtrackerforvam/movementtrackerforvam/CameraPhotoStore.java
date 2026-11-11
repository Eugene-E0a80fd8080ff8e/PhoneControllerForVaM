package local.movementtrackerforvam.movementtrackerforvam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

final class CameraPhotoStore {
    private static final String ALBUM = "VaM";
    private static final String JPEG = "image/jpeg";

    private CameraPhotoStore() {}

    static void save(Context context, byte[] jpeg) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(context.getContentResolver(), jpeg);
        } else {
            saveToPictures(context, jpeg);
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private static void saveToMediaStore(ContentResolver resolver, byte[] jpeg) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "VaM-" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, JPEG);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri picture = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (picture == null) throw new IOException("Could not create photo");

        boolean published = false;
        try {
            try (OutputStream output = resolver.openOutputStream(picture)) {
                if (output == null) throw new IOException("Could not open photo");
                output.write(jpeg);
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(picture, values, null, null) != 1) {
                throw new IOException("Could not publish photo");
            }
            published = true;
        } finally {
            if (!published) resolver.delete(picture, null, null);
        }
    }

    private static void saveToPictures(Context context, byte[] jpeg) throws IOException {
        File album = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM);
        if (!album.mkdirs() && !album.isDirectory()) throw new IOException("Could not create photo album");
        File picture = File.createTempFile("VaM-", ".jpg", album);
        boolean written = false;
        try {
            try (OutputStream output = new FileOutputStream(picture)) {
                output.write(jpeg);
            }
            written = true;
        } finally {
            if (!written) picture.delete();
        }
        MediaScannerConnection.scanFile(context, new String[]{picture.getAbsolutePath()}, new String[]{JPEG}, null);
    }
}
