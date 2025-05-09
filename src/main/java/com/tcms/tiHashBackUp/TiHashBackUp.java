package com.tcms.tiHashBackUp;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class TiHashBackUp extends JavaPlugin {
    private File backupDir;
    private File metadataFile;
    private YamlConfiguration metadata;
    private Map<String, String> fileHashes = new HashMap<>();
    private Map<String, BackupInfo> activeBackups = new HashMap<>();
    private int backupIntervalTicks;
    private long archiveMillis;

    @Override
    public void onEnable() {
        // 初始化配置文件
        saveDefaultConfig();
        backupIntervalTicks = getConfig().getInt("backup.interval-minutes", 10) * 60 * 20;
        archiveMillis = getConfig().getInt("backup.archive-days", 7) * 24 * 60 * 60 * 1000L;

        // 初始化目錄和元數據
        backupDir = new File(getDataFolder(), "backups");
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }

        metadataFile = new File(getDataFolder(), "file_metadata.yml");
        metadata = YamlConfiguration.loadConfiguration(metadataFile);
        loadMetadata();

        // 定時備份任務
        new BukkitRunnable() {
            @Override
            public void run() {
                checkAndBackupChangedFiles();
            }
        }.runTaskTimerAsynchronously(this, 0L, backupIntervalTicks);
    }

    private void loadMetadata() {
        // 載入檔案哈希
        if (metadata.getConfigurationSection("hashes") != null) {
            for (String key : metadata.getConfigurationSection("hashes").getKeys(false)) {
                fileHashes.put(key.replace("_", File.separator), metadata.getString("hashes." + key));
            }
        }

        // 載入活躍備份
        if (metadata.getConfigurationSection("backups") != null) {
            for (String world : metadata.getConfigurationSection("backups").getKeys(false)) {
                String file = metadata.getString("backups." + world + ".file");
                long created = metadata.getLong("backups." + world + ".created");
                activeBackups.put(world, new BackupInfo(file, created));
            }
        }
    }

    private void saveMetadata() {
        // 保存檔案哈希
        metadata.set("hashes", null); // 清空舊數據
        for (Map.Entry<String, String> entry : fileHashes.entrySet()) {
            metadata.set("hashes." + entry.getKey().replace(File.separator, "_"), entry.getValue());
        }

        // 保存活躍備份
        metadata.set("backups", null); // 清空舊數據
        for (Map.Entry<String, BackupInfo> entry : activeBackups.entrySet()) {
            metadata.set("backups." + entry.getKey() + ".file", entry.getValue().file);
            metadata.set("backups." + entry.getKey() + ".created", entry.getValue().created);
        }

        try {
            metadata.save(metadataFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save metadata: " + e.getMessage());
        }
    }

    private String calculateFileHash(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream is = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("Failed to calculate hash for " + file, e);
        }
    }

    private void checkAndBackupChangedFiles() {
        List<String> worlds = getConfig().getStringList("backup.worlds");

        for (String worldName : worlds) {
            // 確保世界數據刷新
            if (getServer().getWorld(worldName) != null) {
                getServer().getWorld(worldName).save();
            }

            File regionDir = new File(getServer().getWorldContainer(), worldName + "/region");
            File worldBackupDir = new File(backupDir, worldName);
            if (!worldBackupDir.exists()) {
                worldBackupDir.mkdirs();
            }

            if (!regionDir.exists()) {
                getLogger().warning("Region directory not found for world: " + worldName);
                continue;
            }

            // 檢查封存
            BackupInfo backupInfo = activeBackups.get(worldName);
            if (backupInfo != null && System.currentTimeMillis() - backupInfo.created >= archiveMillis) {
                // 封存當前備份，創建新備份
                activeBackups.put(worldName, new BackupInfo("backup_" + System.currentTimeMillis() + ".zip",
                        System.currentTimeMillis()));
                saveMetadata();
                getLogger().info("Archived backup for " + worldName + ": " + backupInfo.file);
            } else if (backupInfo == null) {
                // 初始化新備份
                activeBackups.put(worldName, new BackupInfo("backup_" + System.currentTimeMillis() + ".zip",
                        System.currentTimeMillis()));
                saveMetadata();
            }

            // 檢查 .mca 文件
            List<File> changedFiles = new ArrayList<>();
            for (File file : regionDir.listFiles((dir, name) -> name.endsWith(".mca"))) {
                String filePath = file.getAbsolutePath();
                try {
                    String currentHash = calculateFileHash(file);
                    String prevHash = fileHashes.get(filePath);

                    if (prevHash == null || !prevHash.equals(currentHash)) {
                        changedFiles.add(file);
                        fileHashes.put(filePath, currentHash);
                    }
                } catch (IOException e) {
                    getLogger().warning("Failed to check file " + filePath + ": " + e.getMessage());
                }
            }

            if (changedFiles.isEmpty()) {
                continue;
            }

            // 備份到活躍的 ZIP 文件
            File backupFile = new File(worldBackupDir, activeBackups.get(worldName).file);
            try (FileOutputStream fos = new FileOutputStream(backupFile, true); // 追加模式
                 ZipOutputStream zos = new ZipOutputStream(fos)) {
                for (File file : changedFiles) {
                    String entryName = file.getAbsolutePath()
                            .replace(getServer().getWorldContainer().getAbsolutePath() + File.separator, "")
                            .replace(File.separator, "/");
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file.toPath(), zos);
                    zos.closeEntry();
                }
                zos.finish();
                getLogger().info("Updated backup for " + worldName + ": " + backupFile.getName());
            } catch (IOException e) {
                getLogger().warning("Failed to update backup for " + worldName + ": " + e.getMessage());
            }
        }

        // 更新元數據
        saveMetadata();

        // 清理舊備份
        cleanupOldBackups();
    }

    private void cleanupOldBackups() {
        int maxBackups = getConfig().getInt("backup.max-backups", 50);
        for (String worldName : getConfig().getStringList("backup.worlds")) {
            File worldBackupDir = new File(backupDir, worldName);
            File[] backups = worldBackupDir.listFiles((dir, name) -> name.endsWith(".zip"));
            if (backups == null || backups.length <= maxBackups) continue;

            Arrays.sort(backups, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = maxBackups; i < backups.length; i++) {
                if (backups[i].delete()) {
                    getLogger().info("Deleted old backup for " + worldName + ": " + backups[i].getName());
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("tihashbackup") && sender.hasPermission("tihashbackup.use")) {
            if (args.length == 0) {
                sender.sendMessage("Usage: /tihashbackup [save|list|restore] [world] [filename]");
                return true;
            }

            if (args[0].equalsIgnoreCase("save")) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        checkAndBackupChangedFiles();
                        sender.sendMessage("Backup saved!");
                    }
                }.runTaskAsynchronously(this);
            } else if (args[0].equalsIgnoreCase("list")) {
                String worldName = args.length > 1 ? args[1] : null;
                if (worldName != null) {
                    File worldBackupDir = new File(backupDir, worldName);
                    File[] files = worldBackupDir.listFiles((dir, name) -> name.endsWith(".zip"));
                    if (files == null || files.length == 0) {
                        sender.sendMessage("No backups found for world: " + worldName);
                    } else {
                        sender.sendMessage("Backups for " + worldName + ":");
                        for (File file : files) {
                            sender.sendMessage("- " + file.getName());
                        }
                    }
                } else {
                    sender.sendMessage("Available worlds with backups:");
                    for (String world : getConfig().getStringList("backup.worlds")) {
                        File worldBackupDir = new File(backupDir, world);
                        if (worldBackupDir.exists()) {
                            sender.sendMessage("- " + world);
                        }
                    }
                }
            } else if (args[0].equalsIgnoreCase("restore") && args.length == 3) {
                String worldName = args[1];
                String backupName = args[2];
                File backupFile = new File(backupDir, worldName + "/" + backupName);
                if (!backupFile.exists()) {
                    sender.sendMessage("Backup file not found: " + backupName);
                    return true;
                }

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(backupFile))) {
                            ZipEntry entry;
                            while ((entry = zis.getNextEntry()) != null) {
                                File outFile = new File(getServer().getWorldContainer(), entry.getName());
                                outFile.getParentFile().mkdirs();
                                Files.copy(zis, outFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                            sender.sendMessage("Restored " + worldName + " from " + backupName +
                                    ". Please restart the server to apply changes.");
                        } catch (IOException e) {
                            sender.sendMessage("Failed to restore: " + e.getMessage());
                        }
                    }
                }.runTaskAsynchronously(this);
            }
            return true;
        }
        return false;
    }

    private static class BackupInfo {
        String file;
        long created;

        BackupInfo(String file, long created) {
            this.file = file;
            this.created = created;
        }
    }
}