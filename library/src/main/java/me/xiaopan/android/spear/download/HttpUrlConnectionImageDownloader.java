/*
 * Copyright (C) 2013 Peng fei Pan <sky@xiaopan.me>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.xiaopan.android.spear.download;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;

import me.xiaopan.android.spear.Spear;
import me.xiaopan.android.spear.request.DownloadRequest;

/**
 * 使用HttpURLConnection来访问网络的下载器
 */
public class HttpUrlConnectionImageDownloader implements ImageDownloader {
	private static final String NAME = HttpUrlConnectionImageDownloader.class.getSimpleName();

    private Set<String> downloadingFiles;
	private Map<String, ReentrantLock> urlLocks;
    private int maxRetryCount = 1;
    private int timeOut = 15 * 1000;

	public HttpUrlConnectionImageDownloader() {
		this.urlLocks = Collections.synchronizedMap(new WeakHashMap<String, ReentrantLock>());
		this.downloadingFiles = Collections.synchronizedSet(new HashSet<String>());
	}

    @Override
    public void setMaxRetryCount(int maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    @Override
    public void setTimeOut(int timeOut) {
        this.timeOut = timeOut;
    }

    /**
     * 获取一个URL锁，通过此锁可以防止重复下载
     * @param url 下载地址
     * @return URL锁
     */
	public synchronized ReentrantLock getUrlLock(String url){
		ReentrantLock urlLock = urlLocks.get(url);
		if(urlLock == null){
			urlLock = new ReentrantLock();
			urlLocks.put(url, urlLock);
		}
		return urlLock;
	}

	@Override
	public synchronized boolean isDownloadingByCacheFilePath(String cacheFilePath) {
		return downloadingFiles.contains(cacheFilePath);
	}

    @Override
	public Object download(DownloadRequest request) {
        // 根据下载地址加锁，防止重复下载
        ReentrantLock urlLock = getUrlLock(request.getUri());
        urlLock.lock();

        Object result = null;
        int number = 0;
        while(true){
            try {
                result = realDownload(request);
                break;
            } catch (Throwable e) {
                if(e instanceof CanceledException){
                    break;
                }else{
                    boolean retry = (e instanceof SocketTimeoutException || e instanceof InterruptedIOException) && number < maxRetryCount;
                    if(retry){
                        number++;
                        Log.w(Spear.LOG_TAG, NAME+"；"+"下载异常"+"；"+"再次尝试" + "；" + request.getName());
                    }else{
                        Log.e(Spear.LOG_TAG, NAME+"；"+"下载异常"+"；"+"不再尝试" + "；" + request.getName());
                    }
                    e.printStackTrace();
                    if(!retry){
                        break;
                    }
                }
            }
        }

        // 释放锁
        urlLock.unlock();
        return result;
    }

    private Object realDownload(DownloadRequest request) throws Throwable {
        // 如果已经取消了就直接结束
        if (request.isCanceled()) {
            if (request.getSpear().isDebugMode()) {
                Log.w(Spear.LOG_TAG, NAME + "：" + "已取消下载 - get lock" + "；" + request.getName());
            }
            return null;
        }

        // 如果缓存文件已经存在了就直接返回缓存文件
        File cacheFile = request.getCacheFile();
        if (cacheFile != null && cacheFile.exists()) {
            return cacheFile;
        }

        // 下载
        boolean saveToCacheFile = false;  // 是否将数据保存到缓存文件里
        String lockedFilePath = null;   // 已锁定的文件的路径
        InputStream inputStream = null;
        OutputStream outputStream = null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(request.getUri()).openConnection();
            connection.setConnectTimeout(timeOut);
            connection.setReadTimeout(timeOut);

            // 检查状态码
            int responseCode = connection.getResponseCode();
            if (request.isCanceled()) {
                if (request.getSpear().isDebugMode()) {
                    Log.w(Spear.LOG_TAG, NAME + "：" + "已取消下载 - get response code" + "；" + request.getName());
                }
                throw new CanceledException();
            }
            if (responseCode >= 300) {
                throw new IllegalStateException("状态异常，状态码："+responseCode + " 原因：" + connection.getResponseMessage());
            }

            // 检查内容长度
            long  contentLength = connection.getHeaderFieldInt("Content-Length", -1);
            if (contentLength <= 0) {
                throw new IOException("Content-Length 为 0");
            }

            // 根据需求创建缓存文件并标记为正在下载
            saveToCacheFile = cacheFile != null && request.getSpear().getDiskCache().applyForSpace(contentLength) && createFile(cacheFile);
            if (request.isCanceled()) {
                if (request.getSpear().isDebugMode()) {
                    Log.w(Spear.LOG_TAG, NAME + "：" + "已取消下载 - create cache file" + "；" + request.getName());
                }
                throw new CanceledException();
            }

            // 锁定文件，标记为正在下载
            if (saveToCacheFile) {
                downloadingFiles.add(lockedFilePath = cacheFile.getPath());
            }

            // 获取输入流后判断是否已取消
            inputStream = connection.getInputStream();
            if (request.isCanceled()) {
                if (request.getSpear().isDebugMode()) {
                    Log.w(Spear.LOG_TAG, NAME + "：" + "已取消下载 - get input stream" + "；" + request.getName());
                }
                throw new CanceledException();
            }

            // 根据是否需要缓存到本地创建不同的输出流
            ByteArrayOutputStream byteArrayOutputStream = null;
            outputStream = new BufferedOutputStream(saveToCacheFile ? new FileOutputStream(cacheFile, false) : (byteArrayOutputStream = new ByteArrayOutputStream()), 8 * 1024);

            // 读取数据
            long completedLength = copy(inputStream, outputStream, request, contentLength);
            if (request.isCanceled()) {
                if (request.getSpear().isDebugMode()) {
                    Log.w(Spear.LOG_TAG, NAME + "：" + "已取消下载 - read data end" + "；" + request.getName());
                }
                throw new CanceledException();
            }

            // 转换结果
            Object result = saveToCacheFile ? cacheFile : byteArrayOutputStream.toByteArray();
            if (request.getSpear().isDebugMode()) {
                Log.i(Spear.LOG_TAG, NAME + "：" + "下载成功" + "；" + "文件长度：" + completedLength + "/" + contentLength + "；" + request.getName());
            }

            // 各种关闭
            try { outputStream.flush(); } catch (IOException e) { e.printStackTrace(); }
            try { outputStream.close(); } catch (IOException e) { e.printStackTrace(); }
            try { inputStream.close(); } catch (IOException e) { e.printStackTrace(); }
            connection.disconnect();

            // 解除文件锁定
            if (lockedFilePath != null) {
                downloadingFiles.remove(lockedFilePath);
            }

            return result;
        } catch (Throwable throwable) {
            // 各种关闭
            if(outputStream != null){
                try { outputStream.flush(); } catch (IOException e) { e.printStackTrace(); }
                try { outputStream.close(); } catch (IOException e) { e.printStackTrace(); }
            }
            if(inputStream != null){
                try { inputStream.close(); } catch (IOException e) { e.printStackTrace(); }
            }
            if(connection != null){
                connection.disconnect();
            }

            // 如果发生异常并且使用了缓存文件以及缓存文件存在就删除缓存文件，然后如果删除失败就输出LOG
            if(saveToCacheFile && cacheFile != null && cacheFile.exists() && !cacheFile.delete()) {
                Log.e(Spear.LOG_TAG, NAME + "：" + "删除缓存文件失败：" + cacheFile.getPath());
            }

            // 解除文件锁定
            if (lockedFilePath != null) {
                downloadingFiles.remove(lockedFilePath);
            }
            throw throwable;
        }
    }

    public long copy(InputStream inputStream, OutputStream outputStream, DownloadRequest downloadRequest, long contentLength) throws IOException {
        int readNumber;	//读取到的字节的数量
        long completedLength = 0;
        long averageLength = contentLength/10;
        int callbackNumber = 0;
        byte[] cacheBytes = new byte[1024*4];//数据缓存区
        while(!downloadRequest.isCanceled() && (readNumber = inputStream.read(cacheBytes)) != -1){
            outputStream.write(cacheBytes, 0, readNumber);
            completedLength += readNumber;
            if(downloadRequest.getDownloadProgressCallback() != null && (completedLength >= (callbackNumber+1)*averageLength || completedLength == contentLength)){
                callbackNumber++;
                downloadRequest.getDownloadProgressCallback().onUpdateProgress(contentLength, completedLength);
            }
        }
        outputStream.flush();
        return completedLength;
    }

    /**
     * 创建文件，一定要保证给定的文件是存在的，创建的关键是如果文件所在的目录不存在的话就先创建目录
     * @param file 要创建的文件
     * @return true：创建好了
     */
    public static boolean createFile(File file){
        if(!file.exists()){
            File parentDir = file.getParentFile();
            if(!parentDir.exists()){
                parentDir.mkdirs();
            }
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return file.exists();
    }

    private static class CanceledException extends IOException{
    }
}
