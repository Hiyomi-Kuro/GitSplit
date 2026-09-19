package com.android.gitdplit;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A small Storage Access Framework based utility for producing and restoring one-file ZIP volumes.
 * Every generated part except the final one is exactly 3 MiB. The parts must stay together.
 */
public class MainActivity extends AppCompatActivity {
    private static final int VOLUME_SIZE_BYTES = 3 * 1024 * 1024;
    private static final Pattern PART_NAME = Pattern.compile("^(.+)\\.zip\\.(\\d+)$");
    private static final Pattern BANDIZIP_PART_NAME = Pattern.compile("^(.+)\\.z(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final byte[] SPLIT_SIGNATURE = {'P', 'K', 7, 8};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Uri selectedSource;
    private List<Uri> selectedParts = new ArrayList<>();
    private TextView status;
    private android.widget.ProgressBar progressBar;
    private MaterialButton compressButton;
    private MaterialButton extractButton;

    private final ActivityResultLauncher<Intent> outputFolderPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null || selectedSource == null) return;
                Uri folder = result.getData().getData();
                persistPermission(folder, result.getData().getFlags());
                compress(selectedSource, folder);
            });

    private final ActivityResultLauncher<Intent> extractFolderPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null || selectedParts.isEmpty()) return;
                Uri folder = result.getData().getData();
                persistPermission(folder, result.getData().getFlags());
                extract(selectedParts, folder);
            });

    private final ActivityResultLauncher<Intent> sourcePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                selectedSource = result.getData().getData();
                persistPermission(selectedSource, result.getData().getFlags());
                setStatus("已选择源文件：" + displayName(selectedSource) + "\n请选择保存分卷的文件夹。");
                outputFolderPicker.launch(createTreeIntent());
            });

    private final ActivityResultLauncher<Intent> partsPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                selectedParts = collectUris(result.getData());
                for (Uri uri : selectedParts) persistPermission(uri, result.getData().getFlags());
                if (selectedParts.isEmpty()) return;
                setStatus("已选择 " + selectedParts.size() + " 个分卷。\n请选择恢复原文件的输出文件夹。");
                extractFolderPicker.launch(createTreeIntent());
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());
    }

    private View createContent() {
        int padding = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);
        content.setBackgroundColor(0xFF121318);

        TextView subtitle = new TextView(this);
        subtitle.setText("3 MB 分卷压缩与恢复工具");
        subtitle.setTextSize(16);
        subtitle.setTextColor(0xFFCAC4D0);
        content.addView(subtitle, wrapWithTop(4));

        MaterialCardView infoCard = new MaterialCardView(this);
        infoCard.setRadius(dp(18));
        infoCard.setCardElevation(dp(2));
        infoCard.setCardBackgroundColor(0xFF1D1B20);
        TextView description = new TextView(this);
        description.setText("大文件压缩为 Bandizip 风格 .z01、.z02…、.zip 分卷。\n\n"
                + "每卷最大 3 MiB，支持批量选择分卷并恢复原始文件。");
        description.setTextSize(15);
        description.setTextColor(0xFFE6E0E9);
        description.setPadding(dp(16), dp(16), dp(16), dp(16));
        infoCard.addView(description);
        content.addView(infoCard, wrapWithTop(24));

        TextView feature = new TextView(this);
        feature.setText("✓ ZIP 9 级压缩\n✓ 自动编号与完整性检查\n✓ 无需全盘存储权限");
        feature.setTextSize(15);
        feature.setTextColor(0xFFE6E0E9);
        content.addView(feature, wrapWithTop(20));

        compressButton = createActionButton("创建 3 MB 分卷", android.R.drawable.ic_menu_upload);
        compressButton.setOnClickListener(v -> sourcePicker.launch(createOpenIntent(false)));
        content.addView(compressButton, buttonLayout(28));

        extractButton = createActionButton("恢复分卷文件", android.R.drawable.ic_menu_save);
        extractButton.setOnClickListener(v -> partsPicker.launch(createOpenIntent(true)));
        content.addView(extractButton, buttonLayout(12));

        status = new TextView(this);
        status.setText("准备就绪");
        status.setTextSize(14);
        status.setTextColor(0xFFCAC4D0);
        content.addView(status, wrapWithTop(28));

        progressBar = new android.widget.ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        content.addView(progressBar, wrapWithTop(12));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        return scroll;
    }

    private MaterialButton createActionButton(String text, int icon) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setIconResource(icon);
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setIconPadding(dp(12));
        button.setMinHeight(dp(56));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(16));
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private LinearLayout.LayoutParams buttonLayout(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        params.topMargin = dp(top);
        return params;
    }

    private Intent createOpenIntent(boolean allowMultiple) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    private Intent createTreeIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    private void compress(Uri source, Uri treeUri) {
        setBusy(true, "正在以 9 级压缩，请勿关闭应用…");
        worker.execute(() -> {
            MultiVolumeOutputStream volumes = null;
            try {
                DocumentFile tree = requireDirectory(treeUri);
                String sourceName = displayName(source);
                String archiveBase = createUniqueArchiveBase(tree, sourceName);
                volumes = new MultiVolumeOutputStream(getContentResolver(), tree, archiveBase);
                try (InputStream in = new BufferedInputStream(requireInput(source));
                     ZipOutputStream zip = new ZipOutputStream(volumes)) {
                    zip.setLevel(Deflater.BEST_COMPRESSION);
                    zip.putNextEntry(new ZipEntry(safeFileName(sourceName)));
                    copy(in, zip);
                    zip.closeEntry();
                }
                volumes.finish();
                int count = volumes.getPartCount();
                long bytes = volumes.getBytesWritten();
                postDone("压缩完成。已创建 " + count + " 个分卷：\n" + archiveBase
                        + ".z01 … " + archiveBase + ".zip\nZIP 数据总计 " + humanSize(bytes) + "。请保留全部分卷。", false);
            } catch (Exception e) {
                if (volumes != null) volumes.deleteCreatedParts();
                postDone("压缩失败：" + readableError(e), true);
            }
        });
    }

    private void extract(List<Uri> rawParts, Uri treeUri) {
        setBusy(true, "正在验证分卷并恢复文件，请勿关闭应用…");
        worker.execute(() -> {
            DocumentFile output = null;
            try {
                List<Part> parts = validateAndSortParts(rawParts);
                DocumentFile tree = requireDirectory(treeUri);
                try (MultiPartInputStream combined = new MultiPartInputStream(getContentResolver(), parts);
                     ZipInputStream zip = new ZipInputStream(new BufferedInputStream(combined), Charset.forName("GBK"))) {
                    ZipEntry entry = zip.getNextEntry();
                    if (entry == null || entry.isDirectory()) throw new IOException("分卷中没有可恢复的文件");
                    String restoredName = safeFileName(entry.getName());
                    output = createUniqueFile(tree, restoredName);
                    try (OutputStream out = getContentResolver().openOutputStream(output.getUri(), "w")) {
                        if (out == null) throw new IOException("无法写入输出文件");
                        long restored = copy(zip, out);
                        zip.closeEntry();
                        if (zip.getNextEntry() != null) throw new IOException("此应用只支持由 ZipSplit 创建的单文件分卷");
                        postDone("恢复完成：" + output.getName() + "\n已写入 " + humanSize(restored) + "。", false);
                    }
                }
            } catch (Exception e) {
                if (output != null) output.delete();
                postDone("恢复失败：" + readableError(e), true);
            }
        });
    }

    private List<Part> validateAndSortParts(List<Uri> uris) throws IOException {
        boolean containsBandizipPart = false;
        for (Uri uri : uris) {
            if (BANDIZIP_PART_NAME.matcher(displayName(uri)).matches()) {
                containsBandizipPart = true;
                break;
            }
        }
        return containsBandizipPart ? validateBandizipParts(uris) : validateLegacyParts(uris);
    }

    private List<Part> validateBandizipParts(List<Uri> uris) throws IOException {
        List<Part> result = new ArrayList<>();
        String base = null;
        boolean finalZipFound = false;
        for (Uri uri : uris) {
            String name = displayName(uri);
            Matcher partMatcher = BANDIZIP_PART_NAME.matcher(name);
            if (partMatcher.matches()) {
                String currentBase = partMatcher.group(1);
                if (base == null) base = currentBase;
                if (!base.equals(currentBase)) throw new IOException("请选择同一组分卷，不能混入其他文件");
                result.add(new Part(uri, name, Integer.parseInt(partMatcher.group(2))));
            } else if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                String currentBase = name.substring(0, name.length() - 4);
                if (base == null) base = currentBase;
                if (!base.equals(currentBase)) throw new IOException("请选择同一组分卷，不能混入其他文件");
                if (finalZipFound) throw new IOException("同一组分卷只能有一个最后的 .zip 文件");
                finalZipFound = true;
            } else {
                throw new IOException("文件名不是 .z01… .zip 分卷：" + name);
            }
        }
        if (base == null || !finalZipFound) throw new IOException("缺少最后的 .zip 分卷");
        Collections.sort(result, Comparator.comparingInt(part -> part.index));
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).index != i + 1) throw new IOException("分卷不完整：需要 .z"
                    + String.format(Locale.ROOT, "%02d", i + 1));
        }
        for (Uri uri : uris) {
            if (displayName(uri).equalsIgnoreCase(base + ".zip")) {
                result.add(new Part(uri, displayName(uri), result.size() + 1));
                return result;
            }
        }
        throw new IOException("缺少最后的 .zip 分卷");
    }

    private List<Part> validateLegacyParts(List<Uri> uris) throws IOException {
        List<Part> result = new ArrayList<>();
        String base = null;
        for (Uri uri : uris) {
            String name = displayName(uri);
            Matcher matcher = PART_NAME.matcher(name);
            if (!matcher.matches()) throw new IOException("文件名不是 ZipSplit 分卷：" + name);
            String currentBase = matcher.group(1);
            if (base == null) base = currentBase;
            if (!base.equals(currentBase)) throw new IOException("请选择同一组分卷，不能混入其他文件");
            try {
                result.add(new Part(uri, name, Integer.parseInt(matcher.group(2))));
            } catch (NumberFormatException e) {
                throw new IOException("分卷编号无效：" + name);
            }
        }
        Collections.sort(result, Comparator.comparingInt(part -> part.index));
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).index != i + 1) throw new IOException("分卷不完整：需要 .zip."
                    + String.format(Locale.ROOT, "%03d", i + 1));
        }
        return result;
    }

    private DocumentFile requireDirectory(Uri uri) throws IOException {
        DocumentFile directory = DocumentFile.fromTreeUri(this, uri);
        if (directory == null || !directory.isDirectory() || !directory.canWrite()) {
            throw new IOException("所选位置不是可写入的文件夹");
        }
        return directory;
    }

    private String createUniqueArchiveBase(DocumentFile directory, String sourceName) throws IOException {
        String base = safeFileName(sourceName);
        for (int i = 0; i < 10000; i++) {
            String candidate = i == 0 ? base : base + " (" + i + ")";
            if (directory.findFile(candidate + ".z01") == null && directory.findFile(candidate + ".zip") == null) return candidate;
        }
        throw new IOException("无法生成不重复的分卷文件名");
    }

    private DocumentFile createUniqueFile(DocumentFile directory, String requestedName) throws IOException {
        String extension = "";
        String stem = requestedName;
        int dot = requestedName.lastIndexOf('.');
        if (dot > 0) {
            stem = requestedName.substring(0, dot);
            extension = requestedName.substring(dot);
        }
        for (int i = 0; i < 10000; i++) {
            String candidate = i == 0 ? requestedName : stem + " (" + i + ")" + extension;
            if (directory.findFile(candidate) == null) {
                DocumentFile output = directory.createFile("application/octet-stream", candidate);
                if (output != null) return output;
                throw new IOException("无法创建输出文件");
            }
        }
        throw new IOException("无法生成不重复的输出文件名");
    }

    private InputStream requireInput(Uri uri) throws IOException {
        InputStream stream = getContentResolver().openInputStream(uri);
        if (stream == null) throw new IOException("无法读取所选文件");
        return stream;
    }

    private static long copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private void persistPermission(Uri uri, int flags) {
        if (uri == null) return;
        int takeFlags = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (SecurityException ignored) {
            // Some document providers grant a temporary but sufficient permission only.
        }
    }

    private List<Uri> collectUris(Intent data) {
        List<Uri> result = new ArrayList<>();
        if (data.getData() != null) result.add(data.getData());
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) result.add(clip.getItemAt(i).getUri());
        }
        return result;
    }

    private String displayName(Uri uri) {
        DocumentFile file = DocumentFile.fromSingleUri(this, uri);
        String name = file == null ? null : file.getName();
        return name == null || name.trim().isEmpty() ? "source-file" : name;
    }

    private static String safeFileName(String name) {
        String cleaned = name == null ? "source-file" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
        cleaned = cleaned.replace("..", "_").trim();
        return cleaned.isEmpty() ? "source-file" : cleaned;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024d);
        return String.format(Locale.ROOT, "%.2f MiB", bytes / (1024d * 1024d));
    }

    private static String readableError(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private void setStatus(String message) {
        status.setText(message);
    }

    private void setBusy(boolean busy, String message) {
        runOnUiThread(() -> {
            compressButton.setEnabled(!busy);
            extractButton.setEnabled(!busy);
            progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
            status.setText(message);
        });
    }

    private void postDone(String message, boolean failed) {
        runOnUiThread(() -> {
            compressButton.setEnabled(true);
            extractButton.setEnabled(true);
            progressBar.setVisibility(View.GONE);
            status.setText(message);
        });
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWithTop(int top) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(top);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private static final class Part {
        final Uri uri;
        final String name;
        final int index;

        Part(Uri uri, String name, int index) {
            this.uri = uri;
            this.name = name;
            this.index = index;
        }
    }

    private static final class MultiPartInputStream extends InputStream {
        private final ContentResolver resolver;
        private final List<Part> parts;
        private int position;
        private InputStream current;

        MultiPartInputStream(ContentResolver resolver, List<Part> parts) {
            this.resolver = resolver;
            this.parts = parts;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            while (true) {
                if (current == null) {
                    if (position >= parts.size()) return -1;
                    InputStream opened = resolver.openInputStream(parts.get(position++).uri);
                    if (opened == null) throw new IOException("无法读取分卷：" + parts.get(position - 1).name);
                    current = new BufferedInputStream(opened);
                    if (position == 1) skipOptionalSplitSignature();
                }
                int count = current.read(buffer, offset, length);
                if (count != -1) return count;
                current.close();
                current = null;
            }
        }

        @Override
        public void close() throws IOException {
            if (current != null) current.close();
        }

        private void skipOptionalSplitSignature() throws IOException {
            current.mark(SPLIT_SIGNATURE.length);
            for (byte expected : SPLIT_SIGNATURE) {
                if (current.read() != (expected & 0xff)) {
                    current.reset();
                    return;
                }
            }
        }
    }

    private static final class MultiVolumeOutputStream extends OutputStream {
        private final ContentResolver resolver;
        private final DocumentFile directory;
        private final String archiveBase;
        private final List<DocumentFile> created = new ArrayList<>();
        private OutputStream current;
        private int partCount;
        private int partBytes;
        private long bytesWritten;

        MultiVolumeOutputStream(ContentResolver resolver, DocumentFile directory, String archiveBase) throws IOException {
            this.resolver = resolver;
            this.directory = directory;
            this.archiveBase = archiveBase;
            openNextPart();
            write(SPLIT_SIGNATURE);
        }

        @Override
        public void write(int value) throws IOException {
            byte[] one = {(byte) value};
            write(one, 0, 1);
        }

        @Override
        public void write(@NonNull byte[] buffer, int offset, int length) throws IOException {
            int remaining = length;
            int cursor = offset;
            while (remaining > 0) {
                if (partBytes == VOLUME_SIZE_BYTES) openNextPart();
                int count = Math.min(remaining, VOLUME_SIZE_BYTES - partBytes);
                current.write(buffer, cursor, count);
                cursor += count;
                remaining -= count;
                partBytes += count;
                bytesWritten += count;
            }
        }

        @Override
        public void flush() throws IOException {
            if (current != null) current.flush();
        }

        @Override
        public void close() throws IOException {
            if (current != null) {
                current.close();
                current = null;
            }
        }

        int getPartCount() {
            return partCount;
        }

        long getBytesWritten() {
            return bytesWritten;
        }

        void deleteCreatedParts() {
            try {
                close();
            } catch (IOException ignored) { }
            for (DocumentFile file : created) file.delete();
        }

        void finish() throws IOException {
            close();
            DocumentFile finalPart = created.get(created.size() - 1);
            patchEndOfCentralDirectory(finalPart);
            if (!finalPart.renameTo(archiveBase + ".zip")) {
                throw new IOException("无法将最后一个分卷命名为 .zip");
            }
        }

        private void openNextPart() throws IOException {
            if (current != null) current.close();
            partCount++;
            String name = archiveBase + ".z" + String.format(Locale.ROOT, "%02d", partCount);
            DocumentFile file = directory.createFile("application/octet-stream", name);
            if (file == null) throw new IOException("无法创建分卷：" + name);
            OutputStream stream = resolver.openOutputStream(file.getUri(), "w");
            if (stream == null) {
                file.delete();
                throw new IOException("无法写入分卷：" + name);
            }
            created.add(file);
            current = stream;
            partBytes = 0;
        }

        private void patchEndOfCentralDirectory(DocumentFile finalPart) throws IOException {
            byte[] data;
            try (InputStream in = resolver.openInputStream(finalPart.getUri())) {
                if (in == null) throw new IOException("无法读取最后一个分卷");
                data = readAll(in);
            }
            int eocd = findEndOfCentralDirectory(data);
            if (eocd < 0) throw new IOException("ZIP 结束记录缺失");
            long priorVolumeBytes = (long) (partCount - 1) * VOLUME_SIZE_BYTES;
            long centralOffset = readLeInt(data, eocd + 16) - priorVolumeBytes;
            if (centralOffset < 0 || centralOffset > Integer.MAX_VALUE) {
                throw new IOException("ZIP 中央目录不在最后一个分卷中");
            }
            writeLeShort(data, eocd + 4, partCount - 1);
            writeLeShort(data, eocd + 6, partCount - 1);
            writeLeInt(data, eocd + 16, centralOffset);
            try (OutputStream out = resolver.openOutputStream(finalPart.getUri(), "wt")) {
                if (out == null) throw new IOException("无法更新最后一个分卷");
                out.write(data);
            }
        }

        private static byte[] readAll(InputStream in) throws IOException {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }

        private static int findEndOfCentralDirectory(byte[] data) {
            for (int index = data.length - 22; index >= Math.max(0, data.length - 65557); index--) {
                if (readLeInt(data, index) == 0x06054b50L) return index;
            }
            return -1;
        }

        private static long readLeInt(byte[] data, int offset) {
            return ((long) data[offset] & 0xff)
                    | (((long) data[offset + 1] & 0xff) << 8)
                    | (((long) data[offset + 2] & 0xff) << 16)
                    | (((long) data[offset + 3] & 0xff) << 24);
        }

        private static void writeLeShort(byte[] data, int offset, int value) {
            data[offset] = (byte) value;
            data[offset + 1] = (byte) (value >>> 8);
        }

        private static void writeLeInt(byte[] data, int offset, long value) {
            data[offset] = (byte) value;
            data[offset + 1] = (byte) (value >>> 8);
            data[offset + 2] = (byte) (value >>> 16);
            data[offset + 3] = (byte) (value >>> 24);
        }
    }
}
