package com.qianyu.atlas.vcp;

import com.qianyu.atlas.common.BizException;
import com.qianyu.atlas.vcp.VcpDtos.FileContentResponse;
import com.qianyu.atlas.vcp.VcpDtos.NotebookFileView;
import com.qianyu.atlas.vcp.VcpDtos.NotebookSearchResult;
import com.qianyu.atlas.vcp.VcpDtos.NotebookView;
import com.qianyu.atlas.vcp.VcpDtos.TransferResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class VcpNotebookService {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VcpProperties properties;

    public VcpNotebookService(VcpProperties properties) {
        this.properties = properties;
    }

    public List<NotebookView> listNotebooks() {
        return listNotebooks(null);
    }

    public List<NotebookView> listNotebooks(Long userId) {
        Path root = root(userId);
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(this::isNotebookDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .map(path -> new NotebookView(path.getFileName().toString(), countFiles(path), lastModified(path)))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("读取 VCP 日记本失败", exception);
        }
    }

    public NotebookView createNotebook(String name) {
        return createNotebook(null, name);
    }

    public NotebookView createNotebook(Long userId, String name) {
        Path path = notebookPath(userId, name);
        try {
            Files.createDirectories(path);
            return new NotebookView(path.getFileName().toString(), countFiles(path), lastModified(path));
        } catch (IOException exception) {
            throw new UncheckedIOException("创建 VCP 日记本失败", exception);
        }
    }

    public List<NotebookSearchResult> searchNotebooks(String query, int limit) {
        return searchNotebooks(null, query, limit);
    }

    public List<NotebookSearchResult> searchNotebooks(Long userId, String query, int limit) {
        int safeLimit = Math.max(4, Math.min(limit <= 0 ? 12 : limit, 40));
        String normalizedQuery = normalizeQuery(query);
        Path root = root(userId);
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(this::isNotebookDirectory)
                    .map(path -> scoreNotebook(path, normalizedQuery))
                    .filter(result -> normalizedQuery.isBlank() || result.score() > 0)
                    .sorted(Comparator.comparingDouble(NotebookSearchResult::score).reversed()
                            .thenComparing(NotebookSearchResult::lastModified, Comparator.reverseOrder())
                            .thenComparing(NotebookSearchResult::name))
                    .limit(safeLimit)
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("搜索 VCP 日记本失败", exception);
        }
    }

    public NotebookView renameNotebook(String oldName, String newName) {
        return renameNotebook(null, oldName, newName);
    }

    public NotebookView renameNotebook(Long userId, String oldName, String newName) {
        Path oldPath = requireNotebook(userId, oldName);
        Path newPath = notebookPath(userId, newName);
        if (Files.exists(newPath)) {
            throw new BizException("目标日记本已存在");
        }
        try {
            Files.move(oldPath, newPath);
            return new NotebookView(newPath.getFileName().toString(), countFiles(newPath), lastModified(newPath));
        } catch (IOException exception) {
            throw new UncheckedIOException("重命名 VCP 日记本失败", exception);
        }
    }

    public void deleteNotebook(String name, boolean force) {
        deleteNotebook(null, name, force);
    }

    public void deleteNotebook(Long userId, String name, boolean force) {
        Path path = requireNotebook(userId, name);
        try {
            if (!force && countFiles(path) > 0) {
                throw new BizException("日记本非空。请先清理文件，或使用强制删除");
            }
            try (Stream<Path> walk = Files.walk(path)) {
                List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
                for (Path item : paths) Files.deleteIfExists(item);
            }
        } catch (BizException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new UncheckedIOException("删除 VCP 日记本失败", exception);
        }
    }

    public List<NotebookFileView> listFiles(String notebook) {
        return listFiles(null, notebook);
    }

    public List<NotebookFileView> listFiles(Long userId, String notebook) {
        Path path = requireNotebook(userId, notebook);
        try (Stream<Path> stream = Files.list(path)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isTextNote)
                    .sorted(Comparator.comparing(this::lastModifiedInstant).reversed())
                    .map(file -> new NotebookFileView(
                            path.getFileName().toString(),
                            file.getFileName().toString(),
                            size(file),
                            lastModified(file)
                    ))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("读取 VCP 日记文件失败", exception);
        }
    }

    public FileContentResponse readFile(String notebook, String filename) {
        return readFile(null, notebook, filename);
    }

    public FileContentResponse readFile(Long userId, String notebook, String filename) {
        Path file = requireFile(userId, notebook, filename);
        try {
            return new FileContentResponse(notebook, file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new UncheckedIOException("读取 VCP 日记内容失败", exception);
        }
    }

    public FileContentResponse saveFile(String notebook, String filename, String content) {
        return saveFile(null, notebook, filename, content);
    }

    public FileContentResponse saveFile(Long userId, String notebook, String filename, String content) {
        Path file = filePath(userId, notebook, filename);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
            return readFile(userId, notebook, file.getFileName().toString());
        } catch (IOException exception) {
            throw new UncheckedIOException("保存 VCP 日记内容失败", exception);
        }
    }

    public void deleteFile(String notebook, String filename) {
        deleteFile(null, notebook, filename);
    }

    public void deleteFile(Long userId, String notebook, String filename) {
        Path file = requireFile(userId, notebook, filename);
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw new UncheckedIOException("删除 VCP 日记文件失败", exception);
        }
    }

    public TransferResult transferFiles(String sourceNotebook, String targetNotebook, List<String> filenames, boolean overwrite) {
        return transferFiles(null, sourceNotebook, targetNotebook, filenames, overwrite);
    }

    public TransferResult transferFiles(Long userId, String sourceNotebook, String targetNotebook, List<String> filenames, boolean overwrite) {
        Path source = requireNotebook(userId, sourceNotebook);
        Path target = notebookPath(userId, targetNotebook);
        if (source.equals(target)) {
            throw new BizException("源日记本和目标日记本不能相同");
        }
        if (filenames == null || filenames.isEmpty()) {
            throw new BizException("请选择要转移的日记文件");
        }
        try {
            Files.createDirectories(target);
            List<String> messages = new ArrayList<>();
            int moved = 0;
            int skipped = 0;
            for (String filename : filenames) {
                if (!StringUtils.hasText(filename)) {
                    skipped++;
                    continue;
                }
                Path sourceFile = requireFile(userId, source.getFileName().toString(), filename);
                Path targetFile = target.resolve(sanitizeFilename(filename)).normalize();
                ensureInsideRoot(userId, targetFile);
                Path finalTarget = overwrite ? targetFile : nextAvailableFile(userId, targetFile);
                Files.createDirectories(finalTarget.getParent());
                Files.move(sourceFile, finalTarget, overwrite ? new StandardCopyOption[]{StandardCopyOption.REPLACE_EXISTING} : new StandardCopyOption[]{});
                moved++;
                messages.add(sourceFile.getFileName() + " -> " + finalTarget.getFileName());
            }
            return new TransferResult(source.getFileName().toString(), target.getFileName().toString(), moved, skipped, messages);
        } catch (IOException exception) {
            throw new UncheckedIOException("转移 VCP 日记文件失败", exception);
        }
    }

    public TransferResult transferNotebookContents(String sourceNotebook,
                                                   String targetNotebook,
                                                   boolean overwrite,
                                                   boolean deleteSourceWhenEmpty) {
        return transferNotebookContents(null, sourceNotebook, targetNotebook, overwrite, deleteSourceWhenEmpty);
    }

    public TransferResult transferNotebookContents(Long userId,
                                                   String sourceNotebook,
                                                   String targetNotebook,
                                                   boolean overwrite,
                                                   boolean deleteSourceWhenEmpty) {
        Path source = requireNotebook(userId, sourceNotebook);
        Path target = notebookPath(userId, targetNotebook);
        if (source.equals(target)) {
            throw new BizException("源日记本和目标日记本不能相同");
        }
        List<String> filenames = listFiles(userId, source.getFileName().toString()).stream()
                .map(NotebookFileView::filename)
                .toList();
        TransferResult result = transferFiles(userId, source.getFileName().toString(), target.getFileName().toString(), filenames, overwrite);
        if (deleteSourceWhenEmpty && countFiles(source) == 0) {
            try {
                Files.deleteIfExists(source);
            } catch (IOException exception) {
                throw new UncheckedIOException("删除已清空的源日记本失败", exception);
            }
        }
        return result;
    }

    public Path transferExistingFile(Path sourceFile, String targetNotebook, boolean overwrite) {
        return transferExistingFile(null, sourceFile, targetNotebook, overwrite);
    }

    public Path transferExistingFile(Long userId, Path sourceFile, String targetNotebook, boolean overwrite) {
        Path normalized = sourceFile.toAbsolutePath().normalize();
        ensureInsideRoot(userId, normalized);
        if (!Files.exists(normalized) || !Files.isRegularFile(normalized) || !isTextNote(normalized)) {
            throw new BizException(404, "要转移的 VCP 日记文件不存在");
        }
        Path target = notebookPath(userId, targetNotebook);
        Path targetFile = target.resolve(sanitizeFilename(normalized.getFileName().toString())).normalize();
        ensureInsideRoot(userId, targetFile);
        try {
            Files.createDirectories(target);
            Path finalTarget = overwrite ? targetFile : nextAvailableFile(userId, targetFile);
            Files.move(normalized, finalTarget, overwrite ? new StandardCopyOption[]{StandardCopyOption.REPLACE_EXISTING} : new StandardCopyOption[]{});
            return finalTarget;
        } catch (IOException exception) {
            throw new UncheckedIOException("转移已同步记忆文件失败", exception);
        }
    }

    public Path notebookPath(String name) {
        return notebookPath(null, name);
    }

    public Path notebookPath(Long userId, String name) {
        String clean = sanitizeName(name);
        Path path = root(userId).resolve(clean).normalize();
        ensureInsideRoot(userId, path);
        return path;
    }

    public Path filePath(String notebook, String filename) {
        return filePath(null, notebook, filename);
    }

    public Path filePath(Long userId, String notebook, String filename) {
        String cleanFilename = sanitizeFilename(filename);
        Path file = notebookPath(userId, notebook).resolve(cleanFilename).normalize();
        ensureInsideRoot(userId, file);
        return file;
    }

    public String sanitizeName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BizException("日记本名称不能为空");
        }
        String clean = name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (!StringUtils.hasText(clean) || ".".equals(clean) || "..".equals(clean)) {
            throw new BizException("日记本名称无效");
        }
        return clean;
    }

    public String sanitizeFilename(String filename) {
        String clean = StringUtils.hasText(filename) ? filename : "memory.md";
        clean = clean.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (".".equals(clean) || "..".equals(clean)) {
            throw new BizException("日记文件名无效");
        }
        if (!clean.endsWith(".md") && !clean.endsWith(".txt")) clean = clean + ".md";
        return StringUtils.hasText(clean) ? clean : "memory.md";
    }

    public Path root() {
        return root(null);
    }

    public Path root(Long userId) {
        if (!StringUtils.hasText(properties.getDailyNoteRoot())) {
            throw new BizException("请先在 config.env 配置 ATLAS_VCP_DAILYNOTE_ROOT");
        }
        Path root = Path.of(properties.getDailyNoteRoot()).toAbsolutePath().normalize();
        if (properties.isUserScopedRoot()) {
            if (userId == null) {
                throw new BizException(401, "VCP 用户隔离模式需要登录用户");
            }
            root = root.resolve("users").resolve(String.valueOf(userId)).normalize();
        }
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new UncheckedIOException("VCP DailyNote 根目录不可用", exception);
        }
        return root;
    }

    private Path requireNotebook(String name) {
        return requireNotebook(null, name);
    }

    private Path requireNotebook(Long userId, String name) {
        Path path = notebookPath(userId, name);
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            throw new BizException(404, "VCP 日记本不存在");
        }
        return path;
    }

    private Path requireFile(String notebook, String filename) {
        return requireFile(null, notebook, filename);
    }

    private Path requireFile(Long userId, String notebook, String filename) {
        Path file = filePath(userId, notebook, filename);
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new BizException(404, "VCP 日记文件不存在");
        }
        return file;
    }

    private void ensureInsideRoot(Path path) {
        ensureInsideRoot(null, path);
    }

    private void ensureInsideRoot(Long userId, Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(root(userId))) {
            throw new BizException("VCP 文件路径无效");
        }
    }

    private Path nextAvailableFile(Path targetFile) {
        return nextAvailableFile(null, targetFile);
    }

    private Path nextAvailableFile(Long userId, Path targetFile) {
        if (!Files.exists(targetFile)) return targetFile;
        String filename = targetFile.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";
        for (int index = 1; index < 10_000; index++) {
            Path candidate = targetFile.getParent().resolve(base + "_moved-" + index + extension).normalize();
            ensureInsideRoot(userId, candidate);
            if (!Files.exists(candidate)) return candidate;
        }
        throw new BizException("目标日记本中同名文件过多，无法自动命名");
    }

    private boolean isTextNote(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".txt");
    }

    private boolean isNotebookDirectory(Path path) {
        String name = path.getFileName().toString().trim();
        return !name.endsWith("簇");
    }

    private int countFiles(Path notebook) {
        try (Stream<Path> stream = Files.list(notebook)) {
            return (int) stream.filter(Files::isRegularFile).filter(this::isTextNote).count();
        } catch (IOException ignored) {
            return 0;
        }
    }

    private NotebookSearchResult scoreNotebook(Path notebook, String query) {
        String name = notebook.getFileName().toString();
        int fileCount = countFiles(notebook);
        if (query.isBlank()) {
            return new NotebookSearchResult(name, fileCount, lastModified(notebook), recencyScore(notebook), "最近使用", "输入关键词后可按名称、文件名和日记内容召回。");
        }

        String lowerName = name.toLowerCase(Locale.ROOT);
        List<String> terms = queryTerms(query);
        double score = 0;
        List<String> reasons = new ArrayList<>();
        if (lowerName.equals(query)) {
            score += 100;
            reasons.add("名称完全匹配");
        } else if (lowerName.contains(query)) {
            score += 72;
            reasons.add("名称包含关键词");
        }
        double overlap = characterOverlap(query, lowerName);
        if (overlap > 0) {
            score += overlap * 34;
            if (overlap >= 0.35) reasons.add("名称语义相近");
        }
        for (String term : terms) {
            if (lowerName.contains(term)) {
                score += 18;
                if (!reasons.contains("名称词元命中")) reasons.add("名称词元命中");
            }
        }

        NotebookTextMatch textMatch = matchNotebookText(notebook, terms, query);
        score += textMatch.score();
        if (textMatch.score() > 0) reasons.add(textMatch.reason());

        return new NotebookSearchResult(
                name,
                fileCount,
                lastModified(notebook),
                Math.round(score * 10.0) / 10.0,
                reasons.isEmpty() ? "相关度较低" : String.join(" / ", reasons),
                textMatch.snippet().isBlank() ? "日记本名称：" + name : textMatch.snippet()
        );
    }

    private NotebookTextMatch matchNotebookText(Path notebook, List<String> terms, String query) {
        double score = 0;
        String reason = "";
        String snippet = "";
        try (Stream<Path> stream = Files.list(notebook)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isTextNote)
                    .sorted(Comparator.comparing(this::lastModifiedInstant).reversed())
                    .limit(12)
                    .toList();
            for (Path file : files) {
                String filename = file.getFileName().toString();
                String lowerFilename = filename.toLowerCase(Locale.ROOT);
                if (lowerFilename.contains(query)) {
                    score += 28;
                    reason = "文件名命中";
                    snippet = filename;
                }
                for (String term : terms) {
                    if (lowerFilename.contains(term)) {
                        score += 8;
                        if (reason.isBlank()) reason = "文件名词元命中";
                        if (snippet.isBlank()) snippet = filename;
                    }
                }
                String content = readPreview(file, 12_000);
                String lowerContent = content.toLowerCase(Locale.ROOT);
                for (String term : terms) {
                    int index = lowerContent.indexOf(term);
                    if (index >= 0) {
                        score += 10;
                        if (reason.isBlank() || reason.startsWith("文件名")) reason = "日记内容召回";
                        snippet = excerpt(content, index, term.length());
                    }
                }
                if (!snippet.isBlank() && score >= 40) break;
            }
        } catch (IOException ignored) {
        }
        return new NotebookTextMatch(Math.min(score, 90), reason, snippet);
    }

    private String readPreview(Path file, int maxChars) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return content.length() > maxChars ? content.substring(0, maxChars) : content;
        } catch (IOException ignored) {
            return "";
        }
    }

    private String excerpt(String content, int index, int length) {
        int start = Math.max(0, index - 42);
        int end = Math.min(content.length(), index + Math.max(length, 1) + 82);
        String text = content.substring(start, end).replaceAll("\\s+", " ").trim();
        if (start > 0) text = "..." + text;
        if (end < content.length()) text = text + "...";
        return text;
    }

    private String normalizeQuery(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> queryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        for (String term : query.split("[\\s,，。；;、/|]+")) {
            String cleaned = term.trim();
            if (cleaned.length() >= 2) terms.add(cleaned);
        }
        if (terms.isEmpty() && query.length() >= 2) terms.add(query);
        return new ArrayList<>(terms);
    }

    private double characterOverlap(String query, String candidate) {
        Set<Integer> queryChars = codePoints(query);
        if (queryChars.isEmpty()) return 0;
        Set<Integer> candidateChars = codePoints(candidate);
        int matched = 0;
        for (Integer codePoint : queryChars) {
            if (candidateChars.contains(codePoint)) matched++;
        }
        return matched / (double) queryChars.size();
    }

    private Set<Integer> codePoints(String value) {
        Set<Integer> result = new LinkedHashSet<>();
        value.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .filter(codePoint -> Character.isLetterOrDigit(codePoint) || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)
                .forEach(result::add);
        return result;
    }

    private double recencyScore(Path path) {
        long ageHours = Math.max(0, java.time.Duration.between(lastModifiedInstant(path), java.time.Instant.now()).toHours());
        return Math.max(1, 12 - Math.min(11, ageHours / 24.0));
    }

    private String lastModified(Path path) {
        return TIME_FORMATTER.format(lastModifiedInstant(path).atZone(ZoneId.systemDefault()).toLocalDateTime());
    }

    private java.time.Instant lastModifiedInstant(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ignored) {
            return java.time.Instant.EPOCH;
        }
    }

    private long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private record NotebookTextMatch(double score, String reason, String snippet) {
    }
}
