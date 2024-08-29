package org.example;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.apache.commons.codec.digest.DigestUtils;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ModUpdater {
    private static final Logger logger = Logger.getLogger(ModUpdater.class.getName());
    private static final String SFTP_SERVER = "server";
    private static final int SFTP_PORT = 22;
    private static final String SFTP_USER = "user";
    private static final String SFTP_PASS = "pass";
    private static final String REMOTE_DIR = "/mods_client"; // Директория на SFTP сервере, где находятся моды
    private static String localDirPath = "mods"; // Локальная директория для сохранения модов

    private static JProgressBar progressBar;
    private static JTextArea logArea;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ModUpdater::createAndShowGUI);
    }

    private static void createAndShowGUI() {
        JFrame frame = new JFrame("Mod Updater");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 400);
        frame.setLayout(new BorderLayout());

        JButton selectFolderButton = new JButton("Выбрать папку модов");
        selectFolderButton.addActionListener(e -> chooseLocalFolder(frame));

        JButton updateModsButton = new JButton("Обновить моды");
        updateModsButton.addActionListener(e -> new Thread(ModUpdater::updateMods).start());

        progressBar = new JProgressBar();
        progressBar.setStringPainted(true); // Отображение процента выполнения

        logArea = new JTextArea(10, 40);
        logArea.setEditable(false);
        JScrollPane logScrollPane = new JScrollPane(logArea);

        JPanel controlPanel = new JPanel();
        controlPanel.add(selectFolderButton);
        controlPanel.add(updateModsButton);

        frame.add(controlPanel, BorderLayout.NORTH);
        frame.add(progressBar, BorderLayout.CENTER);
        frame.add(logScrollPane, BorderLayout.SOUTH);
        frame.setVisible(true);
    }

    private static void chooseLocalFolder(JFrame parent) {
        JFileChooser folderChooser = new JFileChooser();
        folderChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int result = folderChooser.showOpenDialog(parent);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFolder = folderChooser.getSelectedFile();
            localDirPath = selectedFolder.getAbsolutePath();
            logArea.append("Выбрана локальная папка модов: " + localDirPath + "\n");
        }
    }

    private static void updateMods() {
        JSch jsch = new JSch();
        Session session = null;
        ChannelSftp sftpChannel = null;

        try {
            // Создание локальной папки, если она не существует
            File localDir = new File(localDirPath);
            if (!localDir.exists() && !localDir.mkdir()) {
                logArea.append("Не удалось создать директорию " + localDirPath + "\n");
                return;
            }

            // Установка соединения с SFTP сервером
            session = jsch.getSession(SFTP_USER, SFTP_SERVER, SFTP_PORT);
            session.setPassword(SFTP_PASS);

            // Отключение проверки подлинности хоста (для упрощения, но в реальном проекте это не рекомендуется)
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);

            session.connect();
            sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();

            // Получаем список файлов в удаленной директории
            Set<String> serverMods = new HashSet<>();
            Vector<ChannelSftp.LsEntry> files = sftpChannel.ls(REMOTE_DIR);
            progressBar.setMaximum(files.size()); // Устанавливаем максимальное значение прогресс-бара
            progressBar.setValue(0);

            int progress = 0;
            for (ChannelSftp.LsEntry entry : files) {
                String fileName = entry.getFilename();
                if (!entry.getAttrs().isDir()) {
                    serverMods.add(fileName);
                    File localFile = new File(localDirPath, fileName);

                    // Если файл не существует или его хеш отличается, скачиваем его
                    if (!localFile.exists() || !checkHash(localFile, sftpChannel, REMOTE_DIR + "/" + fileName)) {
                        downloadFile(sftpChannel, REMOTE_DIR + "/" + fileName, localFile);
                    }
                }
                progressBar.setValue(++progress); // Обновляем прогресс-бар
            }

            // Удаляем файлы, которых больше нет на сервере
            File[] localFiles = localDir.listFiles();
            if (localFiles != null) {
                for (File file : localFiles) {
                    if (!serverMods.contains(file.getName())) {
                        logArea.append("Удаление файла: " + file.getName() + "\n");
                        if (!file.delete()) {
                            logArea.append("Не удалось удалить файл: " + file.getName() + "\n");
                        }
                    }
                }
            }

            logArea.append("Обновление завершено!\n");

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Ошибка при обновлении модов", e);
            logArea.append("Ошибка при обновлении модов: " + e.getMessage() + "\n");
        } finally {
            if (sftpChannel != null) {
                sftpChannel.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
            progressBar.setValue(progressBar.getMaximum()); // Устанавливаем прогресс-бар на максимум по завершению
        }
    }

    private static boolean checkHash(File localFile, ChannelSftp sftpChannel, String remoteFilePath) throws IOException, SftpException {
        // Вычисляем хеш локального файла
        String localHash = calculateHash(localFile);

        // Вычисляем хеш удаленного файла
        InputStream inputStream = sftpChannel.get(remoteFilePath);
        String remoteHash = DigestUtils.md5Hex(inputStream);
        inputStream.close();

        return localHash.equals(remoteHash);
    }

    private static String calculateHash(File file) throws IOException {
        try (InputStream fis = new FileInputStream(file)) {
            return DigestUtils.md5Hex(fis);
        }
    }

    private static void downloadFile(ChannelSftp sftpChannel, String remoteFilePath, File localFile) throws SftpException, IOException {
        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(localFile))) {
            InputStream inputStream = sftpChannel.get(remoteFilePath);
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            inputStream.close();
            logArea.append("Скачан: " + localFile.getName() + "\n");
        }
    }
}