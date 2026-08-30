/*
 * Hello Minecraft!.
 * Copyright (C) 2013  huangyuhui <huanghongxun2008@126.com>
 * Portions copyright (C) 2025  PCL Authors
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
package org.jackhuang.hmcl.util.net;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jackhuang.hmcl.util.C;
import org.jackhuang.hmcl.util.code.DigestUtils;
import org.jackhuang.hmcl.api.func.Function;
import org.jackhuang.hmcl.api.HMCLog;
import org.jackhuang.hmcl.core.download.DownloadSourceManager;
import org.jackhuang.hmcl.core.download.IDownloadProvider;
import org.jackhuang.hmcl.util.sys.FileUtils;
import org.jackhuang.hmcl.util.task.Task;
import org.jackhuang.hmcl.util.task.comm.PreviousResult;
import org.jackhuang.hmcl.util.task.comm.PreviousResultRegistrar;
import org.jackhuang.hmcl.util.sys.IOUtils;
import org.jackhuang.hmcl.util.net.DownloadSpeedLimiter;

/**
 * File download task with multi-source fallback support.
 * 
 * Phase 1 Enhancement (from PCL ModNet.vb):
 * - Multiple URL support (URL priority chain)
 * - Automatic fallback to alternate sources on failure
 * - Integration with DownloadSourceManager for health-based selection
 * 
 * @author huangyuhui
 * @see <a href="https://github.com/Meloong-Git/PCL">PCL ModNet.vb NetFile / LoaderDownload</a>
 */
public class FileDownloadTask extends Task implements PreviousResult<File>, PreviousResultRegistrar<String> {

    /** Primary URL */
    protected URL url;
    
    /** Multiple URLs for multi-source download (fallback chain) */
    protected List<URL> urls;
    
    /** Current URL index in the urls list */
    protected int currentUrlIndex = 0;
    
    /** Download source provider for this download */
    protected IDownloadProvider downloadProvider;
    
    protected int downloaded = 0;
    protected File filePath;
    protected String expectedHash;

    /**
     * Callback when download fails - returns a new URL to retry.
     * The integer parameter is the current retry count.
     */
    protected Function<Integer, String> failedCallbackReturnsNewURL;

    public FileDownloadTask setFailedCallbackReturnsNewURL(Function<Integer, String> failedCallbackReturnsNewURL) {
        this.failedCallbackReturnsNewURL = failedCallbackReturnsNewURL;
        return this;
    }

    public FileDownloadTask() {
    }

    public FileDownloadTask(File filePath) {
        this((URL) null, filePath);
    }

    public FileDownloadTask(String url, File filePath) {
        this(IOUtils.parseURL(url), filePath);
    }

    public FileDownloadTask(URL url, File filePath) {
        this(url, filePath, null);
    }

    public FileDownloadTask(String url, File filePath, String hash) {
        this(IOUtils.parseURL(url), filePath, hash);
    }

    public FileDownloadTask(URL url, File file, String hash) {
        this.url = url;
        this.urls = new ArrayList<>();
        if (url != null) this.urls.add(url);
        this.filePath = file;
        this.expectedHash = hash;
    }

    /**
     * Create a task with multiple URLs (fallback chain).
     * The first URL is tried first; on failure, subsequent URLs are tried in order.
     * 
     * Ported from PCL: ModNet.vb NetFile(Urls, LocalPath, Checker)
     * 
     * @param urls   list of download URLs in priority order
     * @param file   local file path
     * @param hash   expected SHA1 hash, or null
     */
    public FileDownloadTask(List<URL> urls, File file, String hash) {
        this.urls = new ArrayList<>(urls);
        this.url = urls.isEmpty() ? null : urls.get(0);
        this.filePath = file;
        this.expectedHash = hash;
    }

    /**
     * Create a task with multiple URLs and a download provider.
     * 
     * @param urls      list of download URLs in priority order
     * @param file      local file path
     * @param hash      expected SHA1 hash, or null
     * @param provider  the download source provider
     */
    public FileDownloadTask(List<URL> urls, File file, String hash, IDownloadProvider provider) {
        this(urls, file, hash);
        this.downloadProvider = provider;
    }

    /**
     * Add additional fallback URLs to the download chain.
     * These will be tried after the primary URLs fail.
     * 
     * @param additionalUrls URLs to add to the fallback chain
     * @return this task for chaining
     */
    public FileDownloadTask addFallbackUrls(List<URL> additionalUrls) {
        if (this.urls == null) {
            this.urls = new ArrayList<>();
            if (this.url != null) this.urls.add(this.url);
        }
        for (URL u : additionalUrls) {
            if (!this.urls.contains(u)) {
                this.urls.add(u);
            }
        }
        return this;
    }

    /**
     * Set the download provider for this task.
     * The provider's fallback chain will be used when URLs from this task fail.
     * 
     * @param provider the download source provider
     * @return this task for chaining
     */
    public FileDownloadTask setDownloadProvider(IDownloadProvider provider) {
        this.downloadProvider = provider;
        return this;
    }

    // Get this download's current URL.
    public String getUrl() {
        return url != null ? url.toString() : null;
    }

    // Get all URLs in the download chain.
    public List<String> getAllUrls() {
        if (urls == null) {
            return url != null ? Arrays.asList(url.toString()) : new ArrayList<>();
        }
        ArrayList<String> result = new ArrayList<>();
        for (URL u : urls) result.add(u.toString());
        return result;
    }

    RandomAccessFile file = null;
    InputStream stream = null;
    boolean shouldContinue = true;

    private void closeFiles() {
        IOUtils.closeQuietly(file);
        file = null;
        IOUtils.closeQuietly(stream);
        stream = null;
    }

    /**
     * Get the next URL in the fallback chain.
     * 
     * @return the next URL, or null if no more URLs available
     */
    private URL getNextUrl() {
        // First, try URLs from our own list
        if (urls != null && currentUrlIndex < urls.size() - 1) {
            currentUrlIndex++;
            return urls.get(currentUrlIndex);
        }
        
        // Then, try URLs from the download provider's fallback chain
        if (downloadProvider != null) {
            List<IDownloadProvider> chain = DownloadSourceManager.getProviderChain(downloadProvider);
            for (IDownloadProvider fallback : chain) {
                if (fallback == downloadProvider) continue;
                // Build URL from fallback provider
                String currentUrlStr = url != null ? url.toString() : "";
                String newUrl = buildFallbackUrl(fallback, currentUrlStr);
                if (newUrl != null) {
                    try {
                        return new URL(newUrl);
                    } catch (Exception e) {
                        HMCLog.warn("Invalid fallback URL: " + newUrl);
                    }
                }
            }
        }
        
        return null;
    }

    /**
     * Build a fallback URL using the given provider's base URL.
     * Extracts the path from the current URL and prepends the provider's base URL.
     */
    private String buildFallbackUrl(IDownloadProvider provider, String currentUrl) {
        if (currentUrl == null || currentUrl.isEmpty()) return null;
        
        try {
            URL current = new URL(currentUrl);
            String path = current.getFile();
            
            // Determine base URL based on provider type
            String baseUrl;
            if (provider instanceof org.jackhuang.hmcl.core.download.BMCLAPIDownloadProvider) {
                baseUrl = provider.getVersionsDownloadURL();
            } else if (provider instanceof org.jackhuang.hmcl.core.download.MojangDownloadProvider) {
                baseUrl = provider.getVersionsDownloadURL();
            } else {
                baseUrl = null;
            }
            
            if (baseUrl != null && !baseUrl.isEmpty()) {
                return baseUrl + path;
            }
        } catch (Exception e) {
            HMCLog.warn("Failed to build fallback URL", e);
        }
        return null;
    }

    /**
     * Record a download success to the source manager.
     */
    private void recordSuccess() {
        if (downloadProvider != null) {
            DownloadSourceManager.recordSuccess(downloadProvider);
        }
    }

    /**
     * Record a download failure to the source manager.
     */
    private void recordFailure() {
        if (downloadProvider != null) {
            DownloadSourceManager.recordFailure(downloadProvider);
        }
    }

    // Download file.
    @Override
    public void executeTask(boolean areDependTasksSucceeded) throws Exception {
        for (PreviousResult<String> p : al)
            this.url = IOUtils.parseURL(p.getResult());

        // Track all URLs we've tried
        ArrayList<String> triedUrls = new ArrayList<>();
        
        for (int repeat = 0; repeat < 12; repeat++) { // Increased from 6 to 12 to accommodate URL chain retries
            if (repeat > 0) {
                // Try the next URL in our fallback chain
                URL nextUrl = getNextUrl();
                if (nextUrl != null) {
                    url = nextUrl;
                    HMCLog.warn("Switching to fallback URL: " + url);
                } else if (failedCallbackReturnsNewURL != null) {
                    // Fall back to the callback (original HMCL behavior)
                    URL tmp = IOUtils.parseURL(failedCallbackReturnsNewURL.apply(repeat));
                    if (tmp != null) {
                        url = tmp;
                        HMCLog.warn("Switch to callback URL: " + url);
                    } else {
                        break; // No more URLs to try
                    }
                } else {
                    break; // No more URLs to try
                }
            }

            // Skip if we've already tried this exact URL
            String urlStr = url != null ? url.toString() : "";
            if (triedUrls.contains(urlStr)) {
                continue;
            }
            triedUrls.add(urlStr);

            HMCLog.log("Downloading: " + url + " to: " + filePath);
            if (!shouldContinue)
                break;
            
            try {
                if (ppl != null)
                    ppl.setProgress(this, -1, 1);

                // Open connection to URL.
                HttpURLConnection con = (HttpURLConnection) url.openConnection();

                con.setDoInput(true);
                con.setConnectTimeout(15000);
                con.setReadTimeout(15000);
                con.setRequestProperty("User-Agent", "Hello Minecraft!");

                // Connect to server.
                con.connect();

                // Make sure response code is in the 200 range.
                int responseCode = con.getResponseCode();
                if (responseCode / 100 != 2) {
                    // For 404/403 on fallback URLs, try next one
                    if (responseCode == HttpURLConnection.HTTP_NOT_FOUND 
                            || responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                        HMCLog.warn("URL returned " + responseCode + ", trying next fallback...");
                        recordFailure();
                        continue;
                    }
                    throw new IOException(C.i18n("download.not_200") + " " + responseCode);
                }

                // Check for valid content length.
                int contentLength = con.getContentLength();
                if (contentLength < 1)
                    throw new IOException("The content length is invalid.");

                if (!FileUtils.makeDirectory(filePath.getParentFile()))
                    throw new IOException("Could not make directory");

                // We use temp file to prevent files from aborting downloading and broken.
                // Ported from PCL: temp file with .hmd extension
                File tempFile = new File(filePath.getAbsolutePath() + ".hmd");
                if (!tempFile.exists())
                    tempFile.createNewFile();
                else if (!tempFile.renameTo(tempFile)) // check file lock
                    throw new IllegalStateException("The temp file is locked, maybe there is an application using the file?");

                // Open file and seek to the end of it.
                file = new RandomAccessFile(tempFile, "rw");

                MessageDigest digest = DigestUtils.getSha1Digest();

                // Lazy-start the global speed limiter.
                // Ported from PCL: ModNet.vb speed limit initialization
                DownloadSpeedLimiter.start();

                stream = con.getInputStream();
                int lastDownloaded = 0;
                downloaded = 0;
                long lastTime = System.currentTimeMillis();
                while (true) {
                    // Size buffer according to how much of the file is left to download.
                    if (!shouldContinue) {
                        closeFiles();
                        filePath.delete();
                        break;
                    }

                    byte buffer[] = new byte[IOUtils.MAX_BUFFER_SIZE];

                    // Read from server into buffer.
                    int read = stream.read(buffer);
                    if (read == -1)
                        break;

                    if (expectedHash != null)
                        digest.update(buffer, 0, read);

                    // Write buffer to file.
                    file.write(buffer, 0, read);
                    downloaded += read;

                    // Speed limit throttling.
                    // Ported from PCL: ModNet.vb line ~996-1000
                    //   While NetTaskSpeedLimitHigh > 0 AndAlso NetTaskSpeedLimitLeft <= 0
                    //       Threading.Thread.Sleep(16)
                    //   End While
                    //   Interlocked.Add(NetTaskSpeedLimitLeft, -RealDataCount)
                    DownloadSpeedLimiter.acquire(read);

                    // Update progress information per second
                    long now = System.currentTimeMillis();
                    if (ppl != null && (now - lastTime) >= 1000) {
                        ppl.setProgress(this, downloaded, contentLength);
                        ppl.setStatus(this, (downloaded - lastDownloaded) / 1024 + "KB/s");
                        lastDownloaded = downloaded;
                        lastTime = now;
                    }
                }
                closeFiles();
                
                // Restore temp file to original name.
                if (aborted)
                    tempFile.delete();
                else {
                    if (filePath.exists())
                        filePath.delete();
                    tempFile.renameTo(filePath);
                }
                if (!shouldContinue)
                    break;
                if (downloaded != contentLength)
                    throw new IllegalStateException("Unexptected file size: " + downloaded + ", expected: " + contentLength);

                // Check hash code
                String hashCode = String.format("%1$040x", new BigInteger(1, digest.digest()));
                if (expectedHash != null && !expectedHash.equalsIgnoreCase(hashCode)) {
                    HMCLog.warn("Hash mismatch: " + hashCode + " != " + expectedHash);
                    // Hash mismatch - try next URL
                    recordFailure();
                    continue;
                }

                // Success!
                recordSuccess();
                if (ppl != null)
                    ppl.onProgressProviderDone(this);
                return;
                
            } catch (IOException | IllegalStateException e) {
                filePath.delete();
                recordFailure();
                setFailReason(new IOException(C.i18n("download.failed") + " " + url, e));
                // Continue to next URL in chain
            } finally {
                closeFiles();
            }
        }
        if (failReason != null)
            throw failReason;
    }

    @Override
    public boolean abort() {
        shouldContinue = false;
        aborted = true;
        return true;
    }

    @Override
    public String getInfo() {
        return C.i18n("download") + ": " + (tag == null ? (url != null ? url.toString() : "?") : tag);
    }

    @Override
    public File getResult() {
        return filePath;
    }

    ArrayList<PreviousResult<String>> al = new ArrayList<>();

    @Override
    public Task registerPreviousResult(PreviousResult<String> pr) {
        al.add(pr);
        return this;
    }
}
