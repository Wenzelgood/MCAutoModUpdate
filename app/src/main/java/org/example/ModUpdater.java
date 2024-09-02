package org.example;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.SFTPException;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ModUpdater {

    // Constants for SFTP connection
    private static final String SFTP_SERVER = "server"; // or using env System.getenv("SFTP_SERVER")
    private static final int SFTP_PORT = 22; // or using env System.getenv("SFTP_PORT")
    private static final String SFTP_USER = "user"; // or using env System.getenv("SFTP_USER")
    private static final String SFTP_PASS = "pass"; // or using env System.getenv("SFTP_PASS")
    private static final String REMOTE_DIR = "/mods_client"; // or using env System.getenv("REMOTE_DIR")

    // GUI components
    private static JProgressBar progressBar;
    private static JTextArea speedArea;
    private static JTextArea logArea;
    private static JButton pauseButton;

    // Download-related constants and variables
    private static final int BUFFER_SIZE = 1024 * 512;
    private static final int THREAD_POOL_SIZE = Math.min(Runtime.getRuntime().availableProcessors(), 8);
    private static final List<String> speedInfo = new CopyOnWriteArrayList<>(
            Collections.nCopies(THREAD_POOL_SIZE, "Скорость потока: 0 KB/s")
    );

    private static final AtomicLong totalBytesDownloaded = new AtomicLong(0);
    private static final AtomicLong totalTime = new AtomicLong(0);
    private static final Logger logger = Logger.getLogger(ModUpdater.class.getName());
    private static String localDirPath = "mods";

    // Configuration
    private static final String CONFIG_FILE = "mod_updater_config.properties";
    private static final Properties config = new Properties();
    private static Set<String> excludedFiles = new HashSet<>();
    private static final Object pauseLock = new Object();
    private static volatile boolean isPaused = false;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ModUpdater::initializeUI);
    }

    private static void initializeUI() {
        loadConfig();
        JFrame frame = createMainFrame();
        setupUIComponents(frame);
        frame.setVisible(true);
    }

    private static JFrame createMainFrame() {
        JFrame frame = new JFrame("Minecraft Auto Mod Update");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 600);
        frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout());
        return frame;
    }

    private static void setupUIComponents(JFrame frame) {
        frame.add(createControlPanel(), BorderLayout.NORTH);
        frame.add(createProgressBar(), BorderLayout.CENTER);
        frame.add(createLogPanel(), BorderLayout.SOUTH);
    }

    private static JPanel createControlPanel() {
        JPanel controlPanel = new JPanel();
        JButton selectFolderButton = new JButton("Выбрать папку модов");
        selectFolderButton.addActionListener(e -> chooseLocalFolder());

        JButton updateModsButton = new JButton("Обновить моды");
        updateModsButton.addActionListener(e -> {
            updateModsButton.setEnabled(false);
            new UpdateModsWorker() {
                @Override
                protected void done() {
                    super.done();
                    updateModsButton.setEnabled(true);
                }
            }.execute();
        });

        pauseButton = new JButton("Пауза");
        pauseButton.addActionListener(e -> togglePause());

        JButton selectExcludedFilesButton = new JButton("Выбрать файлы-исключения");
        selectExcludedFilesButton.addActionListener(e -> selectExcludedFiles());

        controlPanel.add(selectFolderButton);
        controlPanel.add(updateModsButton);
        controlPanel.add(pauseButton);
        controlPanel.add(selectExcludedFilesButton);
        return controlPanel;
    }

    private static JProgressBar createProgressBar() {
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        return progressBar;
    }

    private static JPanel createLogPanel() {
        JPanel logPanel = new JPanel(new GridLayout(2, 1));
        speedArea = new JTextArea(4, 40);
        speedArea.setEditable(false);
        logArea = new JTextArea(10, 40);
        logArea.setEditable(false);

        logPanel.add(new JScrollPane(speedArea));
        logPanel.add(new JScrollPane(logArea));
        return logPanel;
    }

    private static void chooseLocalFolder() {
        JFileChooser folderChooser = new JFileChooser();
        folderChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        try {
            int result = folderChooser.showOpenDialog(null);
            if (result == JFileChooser.APPROVE_OPTION) {
                localDirPath = folderChooser.getSelectedFile().getAbsolutePath();
                SwingUtilities.invokeLater(() -> logArea.append("Выбрана локальная папка модов: " + localDirPath + "\n"));
                config.setProperty("localDirPath", localDirPath);
                saveConfig();
            }
        } catch (Exception e) {
            logError("Ошибка при выборе папки", e);
        }
    }

    private static void togglePause() {
        synchronized (pauseLock) {
            isPaused = !isPaused;
            SwingUtilities.invokeLater(() -> pauseButton.setText(isPaused ? "Продолжить" : "Пауза"));
            if (!isPaused) {
                pauseLock.notifyAll();
            }
        }
    }

    private static void waitIfPaused() throws InterruptedException {
        synchronized (pauseLock) {
            while (isPaused) {
                pauseLock.wait();
            }
        }
    }

    private static void selectExcludedFiles() {
        JFileChooser fileChooser = new JFileChooser(localDirPath);
        fileChooser.setMultiSelectionEnabled(true);
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JAR files", "jar"));
        int result = fileChooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            File[] selectedFiles = fileChooser.getSelectedFiles();
            for (File file : selectedFiles) {
                excludedFiles.add(file.getName());
            }
            config.setProperty("excludedFiles", String.join(",", excludedFiles));
            saveConfig();
            SwingUtilities.invokeLater(() -> logArea.append("Добавлены файлы-исключения: " + String.join(", ", excludedFiles) + "\n"));
        }
    }

    private static void loadConfig() {
        try (FileInputStream in = new FileInputStream(CONFIG_FILE)) {
            config.load(in);
            localDirPath = config.getProperty("localDirPath", "mods");
            String excludedFilesStr = config.getProperty("excludedFiles", "");
            excludedFiles = new HashSet<>(Arrays.asList(excludedFilesStr.split(",")));
        } catch (IOException e) {
            logger.log(Level.WARNING, "Не удалось загрузить конфигурацию. Используются значения по умолчанию.", e);
        }
    }

    private static void saveConfig() {
        try (FileOutputStream out = new FileOutputStream(CONFIG_FILE)) {
            config.store(out, "ModUpdater Configuration");
        } catch (IOException e) {
            logger.log(Level.WARNING, "Не удалось сохранить конфигурацию.", e);
        }
    }

    private static class UpdateModsWorker extends SwingWorker<Set<String>, Void> {
        @Override
        protected Set<String> doInBackground() {
            SwingUtilities.invokeLater(() -> {
                progressBar.setValue(0);
                progressBar.setString("0%");
            });
            return updateMods();
        }

        @Override
        protected void done() {
            try {
                Set<String> serverMods = get();
                SwingUtilities.invokeLater(() -> {
                    removeOldFiles(new File(localDirPath), serverMods);

                    progressBar.setValue(progressBar.getMaximum());
                    progressBar.setString("100%");

                    logArea.append("Обновление модов завершено.\n");
                });
            } catch (Exception e) {
                logError("Ошибка при обновлении модов", e);
            }
        }
    }

    private static Set<String> updateMods() {
        try (SSHClient sshClient = setupSFTPConnection()) {
            try (SFTPClient sftpClient = sshClient.newSFTPClient()) {
                File localDir = new File(localDirPath);
                if (!createLocalDirectory(localDir)) return new HashSet<>();
                List<RemoteResourceInfo> remoteFiles = sftpClient.ls(REMOTE_DIR);
                Set<String> serverMods = extractFileNames(remoteFiles);

                SwingUtilities.invokeLater(() -> initializeProgressBar(remoteFiles.size()));

                boolean hasExistingMods = Objects.requireNonNull(localDir.list((dir, name) -> name.endsWith(".jar"))).length > 0;

                if (hasExistingMods) {
                    removeOldFiles(localDir, serverMods);
                } else {
                    logArea.append("Пропуск удаления: в локальной папке не было существующих модов.\n");
                }

                downloadMods(sftpClient, remoteFiles);

                displayDownloadSummary();
                return serverMods;
            }
        } catch (Exception e) {
            logError("Ошибка при обновлении модов", e);
            return new HashSet<>();
        }
    }

    private static SSHClient setupSFTPConnection() throws IOException {
        SSHClient ssh = new SSHClient();
        ssh.addHostKeyVerifier(new PromiscuousVerifier());

        ssh.setConnectTimeout(15000);
        ssh.setTimeout(60000);

        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                ssh.connect(SFTP_SERVER, SFTP_PORT);
                ssh.authPassword(SFTP_USER, SFTP_PASS);
                return ssh;
            } catch (IOException e) {
                logger.log(Level.WARNING, "Ошибка подключения или аутентификации, попытка " + attempt + " из " + maxRetries, e);
                if (attempt == maxRetries) {
                    throw e;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Прерывание во время ожидания перед повторным подключением", ie);
                }
            }
        }
        throw new IOException("Не удалось установить SFTP соединение");
    }

    private static boolean createLocalDirectory(File localDir) {
        if (!localDir.exists() && !localDir.mkdir()) {
            SwingUtilities.invokeLater(() -> logArea.append("Не удалось создать директорию " + localDirPath + "\n"));
            return false;
        }
        return true;
    }

    private static void initializeProgressBar(int max) {
        SwingUtilities.invokeLater(() -> {
            progressBar.setMaximum(max);
            progressBar.setValue(0);
        });
    }

    private static void downloadMods(SFTPClient sftpClient, List<RemoteResourceInfo> remoteFiles) throws InterruptedException {
        try (ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE)) {
            AtomicInteger progress = new AtomicInteger(0);
            int index = 0;
            for (RemoteResourceInfo fileInfo : remoteFiles) {
                if (fileInfo.getAttributes().getType() == FileMode.Type.REGULAR) {
                    scheduleFileDownload(executor, sftpClient, fileInfo, index++, progress);
                }
            }
            waitForCompletion(executor);
        }
        logArea.append("Загрузка всех модов завершена.\n");
    }

    private static void scheduleFileDownload(ExecutorService executor, SFTPClient sftpClient, RemoteResourceInfo fileInfo, int index, AtomicInteger progress) {
        File localFile = new File(localDirPath, fileInfo.getName());
        final int threadIndex = index % THREAD_POOL_SIZE;

        executor.submit(() -> {
            try {
                waitIfPaused();
                if (!excludedFiles.contains(fileInfo.getName()) && shouldDownloadFile(localFile, sftpClient, fileInfo.getPath())) {
                    downloadFile(sftpClient, fileInfo.getPath(), localFile, threadIndex);
                }
                updateProgressBar(progress);
            } catch (Exception e) {
                logError("Ошибка загрузки файла: " + fileInfo.getName(), e);
            }
        });
    }

    private static boolean shouldDownloadFile(File localFile, SFTPClient sftpClient, String remotePath) throws IOException {
        if (!localFile.exists()) {
            return true;
        }

        try {
            FileAttributes remoteAttrs = sftpClient.stat(remotePath);

            long remoteModTime = remoteAttrs.getMtime() * 1000L;
            long localModTime = localFile.lastModified();

            if (remoteModTime > localModTime) {
                return true;
            }

            return remoteAttrs.getSize() != localFile.length();


        } catch (SFTPException e) {
            logger.log(Level.WARNING, "Error checking remote file: " + remotePath, e);
            return true;
        }
    }

    private static void updateProgressBar(AtomicInteger progress) {
        SwingUtilities.invokeLater(() -> {
            int progressValue = progress.incrementAndGet();
            progressBar.setValue(progressValue);
            progressBar.setString(progressValue * 100 / progressBar.getMaximum() + "%");
        });
    }

    private static void waitForCompletion(ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
            logArea.append("Задачи не завершились за отведенное время\n");
        }
    }

    private static void removeOldFiles(File localDir, Set<String> serverMods) {
        SwingUtilities.invokeLater(() -> {
            int deletedFilesCount = 0;
            for (File file : Objects.requireNonNull(localDir.listFiles())) {
                if (file.isFile() && file.getName().endsWith(".jar") && !serverMods.contains(file.getName()) && !excludedFiles.contains(file.getName())) {
                    if (file.delete()) {
                        logArea.append("Удален файл: " + file.getName() + "\n");
                        deletedFilesCount++;
                    } else {
                        logArea.append("Ошибка удаления файла: " + file.getName() + "\n");
                    }
                }
            }
            logArea.append("Удалено файлов: " + deletedFilesCount + "\n");
        });
    }

    private static void downloadFile(SFTPClient sftpClient, String remoteFilePath, File localFile, int threadIndex) {
        try (RemoteFile remoteFile = sftpClient.open(remoteFilePath);
             InputStream in = remoteFile.new RemoteFileInputStream(0);
             OutputStream out = new FileOutputStream(localFile)) {

            byte[] buffer = new byte[BUFFER_SIZE];
            long startTime = System.currentTimeMillis();
            int bytesRead;
            long totalBytes = 0;

            while ((bytesRead = in.read(buffer)) != -1) {
                waitIfPaused();
                out.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
                totalBytesDownloaded.addAndGet(bytesRead);
                updateSpeedDisplay(threadIndex, totalBytes, startTime);
            }
            totalTime.addAndGet(System.currentTimeMillis() - startTime);
            logArea.append("Скачан: " + localFile.getName() + "\n");
        } catch (IOException e) {
            logError("Ошибка при загрузке файла: " + remoteFilePath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logError("Загрузка файла была прервана: " + remoteFilePath, e);
        }
    }


    private static void updateSpeedDisplay(int threadIndex, long totalBytes, long startTime) {
        long elapsedTime = System.currentTimeMillis() - startTime;
        double speedKBps = (totalBytes / 1024.0) / (elapsedTime / 1000.0);

        speedInfo.set(threadIndex, String.format("Скорость потока %d: %.2f KB/s", threadIndex + 1, speedKBps));
        SwingUtilities.invokeLater(() -> speedArea.setText(String.join("\n", speedInfo)));
    }

    private static Set<String> extractFileNames(List<RemoteResourceInfo> remoteFiles) {
        Set<String> fileNames = new HashSet<>();
        for (RemoteResourceInfo fileInfo : remoteFiles) {
            fileNames.add(fileInfo.getName());
        }
        return fileNames;
    }

    private static void displayDownloadSummary() {
        long totalElapsedTimeSec = totalTime.get() / 1000;
        double avgSpeedKBps = (totalBytesDownloaded.get() / 1024.0) / totalElapsedTimeSec;
        SwingUtilities.invokeLater(() -> logArea.append(String.format("Общая скорость загрузки: %.2f KB/s\n", avgSpeedKBps)));
    }

    private static void logError(String message, Exception e) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + ": " + e.getMessage() + "\n");
            if (e.getCause() != null) {
                logArea.append("Причина: " + e.getCause().getMessage() + "\n");
            }
        });
        logger.log(Level.SEVERE, message, e);
    }
}