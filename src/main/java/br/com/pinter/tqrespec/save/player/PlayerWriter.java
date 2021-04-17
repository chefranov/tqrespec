/*
 * Copyright (C) 2021 Emerson Pinter - All Rights Reserved
 */

/*    This file is part of TQ Respec.

    TQ Respec is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    TQ Respec is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with TQ Respec.  If not, see <http://www.gnu.org/licenses/>.
*/

package br.com.pinter.tqrespec.save.player;

import br.com.pinter.tqrespec.Settings;
import br.com.pinter.tqrespec.core.State;
import br.com.pinter.tqrespec.core.UnhandledRuntimeException;
import br.com.pinter.tqrespec.logging.Log;
import br.com.pinter.tqrespec.save.ChangesTable;
import br.com.pinter.tqrespec.tqdata.GameInfo;
import br.com.pinter.tqrespec.util.Constants;
import br.com.pinter.tqrespec.util.Util;
import com.google.inject.Inject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;

public class PlayerWriter {
    private static final System.Logger logger = Log.getLogger(PlayerWriter.class.getName());
    @Inject
    private CurrentPlayerData saveData;

    @Inject
    private GameInfo gameInfo;

    @SuppressWarnings("SameParameterValue")
    private boolean backupSaveGame(String fileName, String playerName) throws IOException {
        File backupDirectory = new File(gameInfo.getSavePath(), Constants.BACKUP_DIRECTORY);
        Path player = Paths.get(fileName);
        SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd_HH");
        String ts = df.format(new Date());
        boolean fullBackup = Settings.getAlwaysFullBackup();
        File destPlayerZip = new File(backupDirectory, String.format("%s%s_%s.zip", playerName, fullBackup ? "-fullbackup" : "", ts));

        //doesn't overwrite previous backup
        if (destPlayerZip.exists() && destPlayerZip.length() > 1) {
            return true;
        }

        if (!backupDirectory.exists() && !backupDirectory.mkdir()) {
            throw new IOException("Unable to create backup directory");
        }

        if (backupDirectory.canWrite()) {
            URI zipUri = URI.create("jar:" + destPlayerZip.toURI().toString());
            HashMap<String, String> zipCreateOptions = new HashMap<>();
            zipCreateOptions.put("create", "true");

            try (FileSystem zipFs = FileSystems.newFileSystem(zipUri, zipCreateOptions)) {
                final Path root = zipFs.getPath("/");
                if (fullBackup) {
                    Files.walkFileTree(player.getParent(), new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            Path subPath = file.subpath(player.getParent().getNameCount() - 1, file.getNameCount());
                            final Path dest = zipFs.getPath(root.toString(), subPath.toString());
                            Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                            if (player.getParent().getNameCount() == dir.getNameCount()) {
                                return FileVisitResult.CONTINUE;
                            }
                            Path subPath = dir.subpath(player.getParent().getNameCount() - 1, dir.getNameCount());

                            final Path createDir = zipFs.getPath(root.toString(), subPath.toString());
                            Files.createDirectories(createDir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } else {
                    Files.createDirectories(zipFs.getPath(root.toString(), "/" + player.getName(player.getNameCount() - 2)));

                    Path destPlayer = zipFs.getPath("/" + player.getName(player.getNameCount() - 2) + "/" + player.getFileName());
                    Files.copy(player, destPlayer, StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            } catch (IOException e) {
                logger.log(System.Logger.Level.ERROR, Constants.ERROR_MSG_EXCEPTION, e);
                return false;
            }
        }

        return false;
    }

    public boolean backupCurrent() throws IOException {
        String playerChr = saveData.getPlayerChr().toString();
        String playerName = saveData.getPlayerName();
        return this.backupSaveGame(playerChr, playerName);
    }

    public boolean saveCurrent() {
        if (State.get().getSaveInProgress() != null && State.get().getSaveInProgress()) {
            return false;
        }
        State.get().setSaveInProgress(true);
        String playerChr = saveData.getPlayerChr().toString();
        try {
            this.writeBuffer(playerChr);
            State.get().setSaveInProgress(false);
            return true;
        } catch (IOException e) {
            State.get().setSaveInProgress(false);
            throw new UnhandledRuntimeException("Error saving character", e);
        }
    }

    private void writeBuffer(String filename) throws IOException {
        this.writeBuffer(filename, saveData.getChanges());
    }

    private void writeBuffer(String filename, ChangesTable changesTable) throws IOException {
        saveData.getBuffer().rewind();
        List<Integer> changedOffsets = new ArrayList<>(changesTable.keySet());
        Collections.sort(changedOffsets);

        File out = new File(filename);
        try (FileChannel outChannel = new FileOutputStream(out).getChannel()) {

            for (int offset : changedOffsets) {
                int rawCount = offset - saveData.getBuffer().position();
                saveData.getBuffer().limit(rawCount +
                        saveData.getBuffer().position()
                );
                outChannel.write(saveData.getBuffer());
                saveData.getBuffer().limit(saveData.getBuffer().capacity());
                byte[] c = changesTable.get(offset);
                outChannel.write(ByteBuffer.wrap(c));
                int previousValueLength = changesTable.getValuesLengthIndex().get(offset);
                saveData.getBuffer().position(
                        saveData.getBuffer().position() + previousValueLength);
            }

            while (true) {
                if (outChannel.write(saveData.getBuffer()) <= 0) break;
            }

            saveData.getBuffer().rewind();
            outChannel.force(false);
        }
    }

    public void copyCurrentSave(String toPlayerName) throws IOException {
        State.get().setSaveInProgress(true);
        String path;
        if (saveData.isCustomQuest()) {
            path = gameInfo.getSaveDataUserPath();
        } else {
            path = gameInfo.getSaveDataMainPath();
        }

        String fromPlayerName = saveData.getPlayerName();

        Path playerSaveDirSource = Paths.get(path, "_" + fromPlayerName);
        Path playerSaveDirTarget = Paths.get(path, "_" + toPlayerName);

        if (Files.exists(playerSaveDirTarget)) {
            State.get().setSaveInProgress(false);
            throw new FileAlreadyExistsException("Target Directory already exists");
        }
        Util.copyDirectoryRecurse(playerSaveDirSource, playerSaveDirTarget, false);

        ChangesTable changesTable = (ChangesTable) saveData.getChanges().deepClone();

        changesTable.setString("myPlayerName", toPlayerName, true);

        this.writeBuffer(Paths.get(playerSaveDirTarget.toString(), "Player.chr").toString(), changesTable);
        State.get().setSaveInProgress(false);
    }
}
