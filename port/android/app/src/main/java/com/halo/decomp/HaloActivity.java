package com.halo.decomp;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.libsdl.app.SDLActivity;

/**
 * The game: SDL3's activity, running libmain.so (port/android/host), which
 * loads the game image from the APK's assets.
 */
public class HaloActivity extends SDLActivity {
    /** lets system link's broadcasts in over Wi-Fi while the game runs */
    private WifiManager.MulticastLock multicastLock;
    /**
     * the latest system gesture insets {left, top, right, bottom}, in pixels;
     * written on the UI thread, read by the game's thread: replaced as a
     * whole, never changed in place
     */
    private volatile int[] gestureInsets = new int[] { 0, 0, 0, 0 };
    /** the on-screen touch controls, over SDL's surface */
    private TouchControls touchControls;
    private static final int EXPORT_LAYOUT = 401, IMPORT_LAYOUT = 402, IMPORT_MAP = 403,
            IMPORT_MOD_FOLDER = 404, IMPORT_MOD_ARCHIVE = 405;
    private String pendingLayoutExport;

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null)
            pendingLayoutExport = savedInstanceState.getString("pending-layout-export");
        if (mLayout != null) {
            touchControls = new TouchControls(this);
            mLayout.addView(touchControls, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferHighestRefreshRate();
        trackGestureInsets();
        acquireMulticastLock();
        // a new version looked for while the game starts
        Updater.start(this);
    }

    /** the system's file picker, for the touch layout; no storage permission */
    public void chooseLayoutFile(boolean export, String configuration) {
        String message = export
            ? "Choose the folder and the name of the touch layout file."
            : "Choose a touch layout file. It replaces your buttons, the look sensitivity and the General settings.";
        new AlertDialog.Builder(this)
            .setTitle(export ? "Export the layout" : "Import a layout")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(export ? "Choose the place" : "Choose the file", (dialog, which) -> {
                Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(export ? "text/plain" : "*/*");
                if (export) {
                    pendingLayoutExport = configuration;
                    intent.putExtra(Intent.EXTRA_TITLE, "halo-touch-layout.halolayout");
                }
                try {
                    startActivityForResult(intent, export ? EXPORT_LAYOUT : IMPORT_LAYOUT);
                } catch (android.content.ActivityNotFoundException e) {
                    pendingLayoutExport = null;
                    Toast.makeText(this, "This device has no file picker.", Toast.LENGTH_LONG).show();
                }
            })
            .show();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pending-layout-export", pendingLayoutExport);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == IMPORT_MOD_FOLDER || request == IMPORT_MOD_ARCHIVE) {
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            Uri source = data.getData();
            new Thread(() -> importMod(request == IMPORT_MOD_FOLDER, source), "halo-mod-import").start();
            return;
        }
if (request != EXPORT_LAYOUT && request != IMPORT_LAYOUT && request != IMPORT_MAP) {
            super.onActivityResult(request, result, data); return;
        }
        if (request == IMPORT_MAP) {
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            Uri document = data.getData();
            new Thread(() -> importMap(document), "halo-map-import").start();
            return;
        }
        String exported = pendingLayoutExport;
        pendingLayoutExport = null;
        if (result != RESULT_OK || data == null || data.getData() == null)
            return;
        Uri document = data.getData();
        // (the file is read and written away from the UI thread)
        new Thread(() -> {
            try {
                if (request == EXPORT_LAYOUT)
                    writeLayoutFile(document, exported);
                else
                    readLayoutFile(document);
            } catch (Exception e) {
                runOnUiThread(() -> layoutFileError(e));
            }
        }, "halo-touch-layout-file").start();
    }

    private void writeLayoutFile(Uri document, String configuration) throws IOException {
        if (configuration == null)
            throw new IOException("the layout to export is gone");
        try (OutputStream output = getContentResolver().openOutputStream(document, "wt")) {
            if (output == null)
                throw new IOException("the file cannot be opened");
            output.write(configuration.getBytes(StandardCharsets.UTF_8));
        }
        runOnUiThread(() -> Toast.makeText(this, "The layout is exported.", Toast.LENGTH_SHORT).show());
    }

    private void readLayoutFile(Uri document) throws IOException {
        ByteArrayOutputStream contents = new ByteArrayOutputStream();
        try (InputStream input = getContentResolver().openInputStream(document)) {
            if (input == null)
                throw new IOException("the file cannot be opened");
            byte[] buffer = new byte[4096];
            int size;
            while ((size = input.read(buffer)) != -1) {
                if (contents.size() + size > 65536)
                    throw new IOException("the file is too large");
                contents.write(buffer, 0, size);
            }
        }
        String configuration = new String(contents.toByteArray(), StandardCharsets.UTF_8);
        // checked here, then applied to the overlay at once
        TouchLayout.importConfiguration(configuration);
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || touchControls == null)
                return;
            try {
                touchControls.importLayout(configuration);
                Toast.makeText(this, "The layout is imported and saved.", Toast.LENGTH_SHORT).show();
            } catch (IllegalArgumentException e) {
                layoutFileError(e);
            }
        });
    }

    /** SAF lets the player choose a map file without storage permissions; it is copied to custom_maps. */
    public void importMapFile() {
        new AlertDialog.Builder(this).setTitle("Import map")
            .setMessage("Choose a Halo Custom Edition map (.map). It is copied to custom_maps beside maps and appears in the level lists.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose file", (dialog, which) -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try { startActivityForResult(intent, IMPORT_MAP); }
                catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(this, "No document picker is available.", Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    private void importMap(Uri document) {
        String name = TouchControls.sanitizeMapName(queryDisplayName(document));
        if (name == null) {
            notifyMapsChanged("That is not a .map file.");
            return;
        }
        if (TouchControls.isResourceMapName(name)) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(this).setTitle("Shared resource map")
                    .setMessage(name+" is shared by every custom map: importing replaces the copy all of them use. Import it?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Import", (dialog, which) ->
                        new Thread(() -> copyImportedMap(document, name), "halo-map-import").start())
                    .show();
            });
            return;
        }
        new Thread(() -> copyImportedMap(document, name), "halo-map-import").start();
    }

    private void notifyMapsChanged(String message) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (touchControls != null) touchControls.onMapsChanged(message);
            else Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void copyImportedMap(Uri document, String name) {
        java.io.File root = getExternalFilesDir(null);
        String message;
        if (root == null) {
            message = "Game storage is unavailable.";
        } else {
            java.io.File directory = new java.io.File(root, "custom_maps");
            java.io.File finalFile = new java.io.File(directory, name);
            boolean replaced = finalFile.isFile();
            message = copyMapDocument(document, directory, finalFile, replaced ? "Replaced " : "Imported ");
            if (TouchControls.isResourceMapName(name))
                message += " It is shared by all custom maps.";
        }
        notifyMapsChanged(message);
    }

    private String copyMapDocument(Uri document, java.io.File directory, java.io.File finalFile, String done) {
        java.io.File partial = new java.io.File(directory, finalFile.getName()+".partial");
        try {
            if (!directory.isDirectory() && !directory.mkdirs())
                throw new java.io.IOException("Cannot create custom_maps");
            try (InputStream input = getContentResolver().openInputStream(document)) {
                if (input == null) throw new java.io.IOException("Cannot open map file");
                try (OutputStream output = new java.io.FileOutputStream(partial)) {
                    byte[] buffer = new byte[1048576]; int size;
                    while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
                }
            }
            if (finalFile.isFile() && !finalFile.delete())
                throw new java.io.IOException("Cannot replace "+finalFile.getName());
            if (!partial.renameTo(finalFile))
                throw new java.io.IOException("Cannot install "+finalFile.getName());
            return done+finalFile.getName()+".";
        } catch (Exception e) {
            partial.delete();
            return "Could not import map: "+e.getMessage();
        }
    }

    /** SAF lets the player choose a mod folder or archive without storage permissions; it is copied to mods. */
    public void importModFolder() {
        new AlertDialog.Builder(this).setTitle("Import mod")
            .setMessage("Choose a mod's folder (with its mod.toml and tags) or a .zip of one. It is copied to mods and appears in the Mods menu.")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("ZIP file", (dialog, which) -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try { startActivityForResult(intent, IMPORT_MOD_ARCHIVE); }
                catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(this, "No document picker is available.", Toast.LENGTH_LONG).show();
                }
            })
            .setPositiveButton("Folder", (dialog, which) -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                try { startActivityForResult(intent, IMPORT_MOD_FOLDER); }
                catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(this, "No folder picker is available.", Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    private void importMod(boolean folder, Uri source) {
        String name = TouchControls.sanitizeModName(folder ? treeDisplayName(source) : queryDisplayName(source));
        if (name == null) {
            notifyMessage("That is not a mod folder or .zip file.");
            return;
        }
        java.io.File root = getExternalFilesDir(null);
        if (root == null) {
            notifyMessage("Game storage is unavailable.");
            return;
        }
        final java.io.File destination = new java.io.File(new java.io.File(root, "mods"), name);
        if (destination.exists()) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(this).setTitle("Replace mod")
                    .setMessage(name+" is already in mods. Replace it?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Replace", (dialog, which) ->
                        new Thread(() -> copyImportedMod(folder, source, destination), "halo-mod-import").start())
                    .show();
            });
            return;
        }
        copyImportedMod(folder, source, destination);
    }

    private void copyImportedMod(boolean folder, Uri source, java.io.File destination) {
        java.io.File parent = destination.getParentFile();
        java.io.File partial = parent == null ? null : new java.io.File(parent, destination.getName()+".partial");
        String message;
        try {
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs()))
                throw new IOException("Cannot create mods");
            deleteTree(partial);
            if (folder) copyDocumentTree(source, partial);
            else extractModArchive(source, partial);
            if (!new java.io.File(partial, "mod.toml").isFile())
                throw new IOException("there is no mod.toml, so it is not a mod");
            deleteTree(destination);
            if (!partial.renameTo(destination))
                throw new IOException("Cannot install "+destination.getName());
            message = "Imported mod "+destination.getName()+".";
        } catch (Exception e) {
            deleteTree(partial);
            message = "Could not import mod: "+e.getMessage();
        }
        notifyMessage(message);
    }

    /** Copied from a folder picked with ACTION_OPEN_DOCUMENT_TREE: its contents become the mod. */
    private void copyDocumentTree(Uri tree, java.io.File destination) throws IOException {
        if (!destination.isDirectory() && !destination.mkdirs())
            throw new IOException("Cannot create the mod folder");
        String rootId = android.provider.DocumentsContract.getTreeDocumentId(tree);
        copyDocumentChildren(tree, rootId, destination, 0);
    }

    private void copyDocumentChildren(Uri tree, String parentId, java.io.File destination, int depth) throws IOException {
        if (depth > 24)
            throw new IOException("the mod's folders are too deep");
        Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (android.database.Cursor cursor = getContentResolver().query(children,
                new String[]{android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null)
                throw new IOException("the folder cannot be read");
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = safeFileName(cursor.getString(1));
                if (name == null) continue;
                java.io.File target = new java.io.File(destination, name);
                if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    if (!target.isDirectory() && !target.mkdirs())
                        throw new IOException("Cannot create "+name);
                    copyDocumentChildren(tree, id, target, depth+1);
                } else {
                    Uri document = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    try (InputStream input = getContentResolver().openInputStream(document)) {
                        if (input == null) throw new IOException("Cannot read "+name);
                        try (OutputStream output = new java.io.FileOutputStream(target)) {
                            byte[] buffer = new byte[65536]; int size;
                            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
                        }
                    }
                }
            }
        }
    }

    /** Copied from a .zip picked with ACTION_OPEN_DOCUMENT; a single wrapping folder is unwrapped. */
    private void extractModArchive(Uri source, java.io.File destination) throws IOException {
        if (!destination.isDirectory() && !destination.mkdirs())
            throw new IOException("Cannot create the mod folder");
        try (InputStream input = getContentResolver().openInputStream(source)) {
            if (input == null) throw new IOException("the file cannot be opened");
            try (java.util.zip.ZipInputStream archive = new java.util.zip.ZipInputStream(input)) {
                byte[] buffer = new byte[65536];
                for (java.util.zip.ZipEntry entry; (entry = archive.getNextEntry()) != null; ) {
                    String entryName = entry.getName().replace('\\', '/');
                    for (String part : entryName.split("/"))
                        if (part.equals("..")) throw new IOException("the archive has an unsafe path");
                    java.io.File target = new java.io.File(destination, entryName);
                    if (entry.isDirectory()) {
                        if (!target.isDirectory() && !target.mkdirs())
                            throw new IOException("Cannot create "+entryName);
                        continue;
                    }
                    java.io.File dir = target.getParentFile();
                    if (dir != null && !dir.isDirectory() && !dir.mkdirs())
                        throw new IOException("Cannot create "+entryName);
                    try (OutputStream output = new java.io.FileOutputStream(target)) {
                        int size;
                        while ((size = archive.read(buffer)) != -1) output.write(buffer, 0, size);
                    }
                }
            }
        }
        unwrapSingleFolder(destination);
    }

    /** A .zip of one folder (the usual download) holds the mod one level down; move it up. */
    private void unwrapSingleFolder(java.io.File destination) throws IOException {
        if (new java.io.File(destination, "mod.toml").isFile()) return;
        java.io.File[] entries = destination.listFiles();
        java.util.ArrayList<java.io.File> candidates = new java.util.ArrayList<>();
        if (entries != null) for (java.io.File entry : entries)
            if (!entry.getName().startsWith(".") && !entry.getName().equals("__MACOSX"))
                candidates.add(entry);
        if (candidates.size() != 1 || !candidates.get(0).isDirectory()) return;
        java.io.File inner = candidates.get(0);
        if (!new java.io.File(inner, "mod.toml").isFile()) return;
        java.io.File[] contents = inner.listFiles();
        if (contents == null) return;
        for (java.io.File item : contents)
            if (!item.renameTo(new java.io.File(destination, item.getName())))
                throw new IOException("Cannot install "+item.getName());
        inner.delete();
    }

    /** The folder's name as Android reports it, for the mod's name in mods. */
    private String treeDisplayName(Uri tree) {
        try {
            String id = android.provider.DocumentsContract.getTreeDocumentId(tree);
            Uri document = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id);
            try (android.database.Cursor cursor = getContentResolver().query(document,
                    new String[]{android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception e) { /* fall through to the URI's last segment */ }
        return queryDisplayName(tree);
    }

    /** One path segment of an imported mod, or null if Android gave nothing usable. */
    private static String safeFileName(String name) {
        if (name == null) return null;
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < name.length() && kept.length() < 200; i++) {
            char c = name.charAt(i);
            kept.append(c == '/' || c == '\\' || c == 0 || c < 32 || c == 127 ? '_' : c);
        }
        String clean = kept.toString().trim();
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) return null;
        return clean;
    }

    private void deleteTree(java.io.File file) {
        if (file == null || !file.exists()) return;
        java.io.File[] children = file.listFiles();
        if (children != null) for (java.io.File child : children) deleteTree(child);
        file.delete();
    }

    private void notifyMessage(String message) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private String queryDisplayName(Uri document) {        try (android.database.Cursor cursor = getContentResolver().query(document,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null) return name;
            }
        } catch (Exception e) { /* fall through to the path segment */ }
        String path = document.getLastPathSegment();
        return path == null ? "" : path;
    }

    private void layoutFileError(Exception error) {
        if (isFinishing() || isDestroyed())
            return;
        new AlertDialog.Builder(this)
            .setTitle("Touch layout file")
            .setMessage("The operation did not complete: " + error.getMessage())
            .setPositiveButton("OK", null)
            .show();
    }

    /** Android Back leaves the Porting options and the layout editor first */
    @Override
    public void onBackPressed() {
        if (touchControls != null && touchControls.menuBack())
            return;
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (touchControls != null && getWindow().getDecorView().hasWindowFocus())
            touchControls.startDeviceInput();
    }

    @Override
    protected void onPause() {
        if (touchControls != null)
            touchControls.stopDeviceInput();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (touchControls != null) {
            if (hasFocus)
                touchControls.startDeviceInput();
            else
                touchControls.stopDeviceInput();
        }
        super.onWindowFocusChanged(hasFocus);
    }

    @Override
    protected void onDestroy() {
        if (touchControls != null)
            touchControls.stopDeviceInput();
        if (multicastLock != null && multicastLock.isHeld())
            multicastLock.release();
        multicastLock = null;
        super.onDestroy();
    }

    /**
     * keeps gestureInsets current; Android sends the insets again when the
     * activity rotates (after the game's native code has started on a phone
     * launched from portrait), so a single read at startup would keep the
     * portrait values; the listener hands the insets on so SDL's own
     * handling still sees them
     */
    private void trackGestureInsets() {
        getWindow().getDecorView().setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                gestureInsets = readGestureInsets(insets);
                return view.onApplyWindowInsets(insets);
            }
        });
    }

    @SuppressWarnings("deprecation") // getSystemGestureInsets is the only call on Android 10
    private static int[] readGestureInsets(WindowInsets insets) {
        Insets gesture;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            gesture = insets.getInsets(WindowInsets.Type.systemGestures());
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            gesture = insets.getSystemGestureInsets();
        else
            return new int[] { 0, 0, 0, 0 };
        return new int[] { gesture.left, gesture.top, gesture.right, gesture.bottom };
    }

    /**
     * the edges of the screen where Android keeps its gestures; in sticky
     * full screen the first swipe from an edge only shows the system bars,
     * and Android hands that swipe to the game as an ordinary finger, so
     * the game (port/linux/src/touch_input.c) must ignore touches that
     * begin there; returns a copy of {left, top, right, bottom} in pixels:
     * all 0 before Android 10 (which has no insets) and until the first
     * insets arrive. The game's native code calls this through JNI by name
     * and signature (host_main.c GetMethodID(...,
     * "getSystemGestureInsetsPixels", "()[I")), at every finger down, so
     * it must not be renamed, retyped or removed as unused
     */
    public int[] getSystemGestureInsetsPixels() {
        return gestureInsets.clone();
    }

    /**
     * Many phones drop the Wi-Fi's broadcast and multicast datagrams to
     * save power unless an app holds this: without it they would not see
     * system link games on the local network, nor be seen hosting one.
     */
    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null)
                return;
            multicastLock = wifi.createMulticastLock("halo-system-link");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (RuntimeException e) {
            // (no Wi-Fi, or not allowed: the local network may miss games)
            multicastLock = null;
        }
    }

    /**
     * The game draws a frame at every display refresh, between its 30 Hz
     * ticks (port/linux/game/render_interpolation.c); Android otherwise
     * often keeps an app at 60 Hz on a faster display.
     */
    private void preferHighestRefreshRate() {
        Display display = getWindowManager().getDefaultDisplay();
        Display.Mode current = display.getMode();
        Display.Mode best = current;

        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth() &&
                mode.getPhysicalHeight() == current.getPhysicalHeight() &&
                mode.getRefreshRate() > best.getRefreshRate()) {
                best = mode;
            }
        }
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.preferredDisplayModeId = best.getModeId();
        getWindow().setAttributes(attributes);
    }
}
