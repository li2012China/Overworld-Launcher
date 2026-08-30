/*
 * Hello Minecraft! Launcher.
 * Copyright (C) 2013  huangyuhui <huanghongxun2008@126.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see {http://www.gnu.org/licenses/}.
 */
package org.jackhuang.hmcl.core.asset;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.jackhuang.hmcl.util.C;
import org.jackhuang.hmcl.api.HMCLog;
import org.jackhuang.hmcl.core.service.IMinecraftAssetService;
import org.jackhuang.hmcl.core.download.IDownloadProvider;
import org.jackhuang.hmcl.core.download.BMCLAPIDownloadProvider;
import org.jackhuang.hmcl.core.download.MojangDownloadProvider;
import org.jackhuang.hmcl.core.version.MinecraftVersion;
import org.jackhuang.hmcl.util.task.Task;
import org.jackhuang.hmcl.util.net.FileDownloadTask;
import org.jackhuang.hmcl.util.code.DigestUtils;
import org.jackhuang.hmcl.util.sys.FileUtils;
import org.jackhuang.hmcl.util.sys.IOUtils;
import org.jackhuang.hmcl.util.task.TaskInfo;

/**
 * Assets
 *
 * @author huangyuhui
 */
public abstract class IAssetsHandler {

    protected ArrayList<String> assetsDownloadURLs;
    protected ArrayList<File> assetsLocalNames;
    protected final String name;
    protected List<AssetsObject> assetsObjects;

    public IAssetsHandler(String name) {
        this.name = name;
    }

    public static final IAssetsHandler ASSETS_HANDLER;

    static {
        ASSETS_HANDLER = new AssetsMojangLoader(C.i18n("assets.list.1_7_3_after"));
    }

    /**
     * interface name
     *
     * @return
     */
    public String getName() {
        return name;
    }

    /**
     * All the files assets needed
     *
     * @param mv The version that needs assets
     * @param mp Asset Service
     * @return just run it!
     */
    public abstract Task getList(MinecraftVersion mv, IMinecraftAssetService mp);

    /**
     * Will be invoked when the user invoked "Download all assets".
     *
     * @param sourceType Download Source
     *
     * @return Download File Task
     */
    public abstract Task getDownloadTask(IDownloadProvider sourceType);

    public abstract boolean isVersionAllowed(String formattedVersion);

    /**
     * Multi-source asset download task.
     * Phase 2 Enhancement (from PCL ModDownload.vb DlSourceAssetsGet):
     * - Each asset file is tried from BMCLAPI first, then Mojang on failure
     * - Uses FileDownloadTask's multi-URL chain support
     */
    protected class AssetsTask extends TaskInfo {

        ArrayList<Task> al;
        /** Primary URL base (BMCLAPI) */
        String primaryBase;
        /** Fallback URL base (Mojang) */
        String fallbackBase;

        /**
         * Single-source constructor (backward compatible).
         */
        public AssetsTask(String url) {
            this(url, null);
        }

        /**
         * Dual-source constructor.
         * 
         * @param primaryBase   primary URL base (BMCLAPI)
         * @param fallbackBase  fallback URL base (Mojang), may be null
         */
        public AssetsTask(String primaryBase, String fallbackBase) {
            super(C.i18n("assets.download"));
            this.primaryBase = primaryBase;
            this.fallbackBase = fallbackBase;
        }

        @Override
        public void executeTask(boolean areDependTasksSucceeded) {
            if (assetsDownloadURLs == null || assetsLocalNames == null || assetsObjects == null)
                throw new IllegalStateException(C.i18n("assets.not_refreshed"));
            int max = assetsDownloadURLs.size();
            al = new ArrayList<>();
            int hasDownloaded = 0;
            for (int i = 0; i < max; i++) {
                String mark = assetsDownloadURLs.get(i);
                File location = assetsLocalNames.get(i);
                if (!FileUtils.makeDirectory(location.getParentFile()))
                    HMCLog.warn("Failed to make directories: " + location.getParent());
                if (location.isDirectory())
                    continue;
                boolean need = true;
                try {
                    if (location.exists()) {
                        FileInputStream fis = FileUtils.openInputStream(location);
                        String sha = DigestUtils.sha1Hex(IOUtils.toByteArray(fis));
                        IOUtils.closeQuietly(fis);
                        if (assetsObjects.get(i).getHash().equals(sha)) {
                            ++hasDownloaded;
                            HMCLog.log("File " + assetsLocalNames.get(i) + " has been downloaded successfully, skipped downloading.");
                            if (ppl != null)
                                ppl.setProgress(this, hasDownloaded, max);
                            continue;
                        }
                    }
                } catch (IOException e) {
                    HMCLog.warn("Failed to get hash: " + location, e);
                    need = !location.exists();
                }
                if (need) {
                    String primaryUrl = primaryBase + mark;
                    String hash = assetsObjects.get(i).getHash();
                    if (fallbackBase != null) {
                        // Dual-source download: BMCLAPI first, Mojang fallback
                        // Ported from PCL: ModDownload.vb DlSourceAssetsGet()
                        String fallbackUrl = buildMojangAssetUrl(mark, hash);
                        if (fallbackUrl != null) {
                            FileDownloadTask task = new FileDownloadTask(
                                Arrays.asList(
                                    toURL(primaryUrl),
                                    toURL(fallbackUrl)
                                ),
                                location,
                                hash
                            );
                            al.add(task.setTag(hash));
                        } else {
                            // Fallback URL building failed, use single source
                            al.add(new FileDownloadTask(primaryUrl, location, hash)
                                .setTag(hash));
                        }
                    } else {
                        // Single-source (backward compatibility)
                        al.add(new FileDownloadTask(primaryUrl, location)
                            .setTag(hash));
                    }
                }
            }
        }

        /**
         * Build the Mojang asset URL from the asset hash path.
         * Assets are stored at: https://resources.download.minecraft.net/{hash[0:2]}/{hash}
         * 
         * Ported from PCL: ModDownload.vb DlSourceAssetsGet()
         */
        private String buildMojangAssetUrl(String bmclapiPath, String hash) {
            if (hash == null || hash.length() < 2) return null;
            // The hash from assets index is the full SHA1
            // Mojang stores assets at resources.download.minecraft.net/{2-char-prefix}/{full-hash}
            String prefix = hash.substring(0, 2);
            return "https://resources.download.minecraft.net/" + prefix + "/" + hash;
        }

        private URL toURL(String url) {
            try {
                return new URL(url);
            } catch (MalformedURLException e) {
                HMCLog.warn("Invalid URL: " + url);
                return null;
            }
        }

        @Override
        public Collection<Task> getAfterTasks() {
            return al;
        }
    }
}
