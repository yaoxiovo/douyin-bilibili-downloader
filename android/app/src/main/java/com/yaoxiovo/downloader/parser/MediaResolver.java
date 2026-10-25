package com.yaoxiovo.downloader.parser;

import com.yaoxiovo.downloader.model.MediaInfo;
import java.io.IOException;

/**
 * Unified media resolver dispatcher for Douyin and Bilibili.
 */
public class MediaResolver {

    /**
     * Resolves raw text / video URL from either Douyin or Bilibili.
     *
     * @param input Raw text containing video URL or direct ID
     * @return Fully resolved MediaInfo
     * @throws IOException If network fails or URL is unsupported
     */
    public static MediaInfo resolve(String input) throws IOException {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("解析内容不能为空");
        }

        String trimmed = input.trim();

        if (BilibiliResolver.isBiliInput(trimmed)) {
            return BilibiliResolver.resolve(trimmed);
        }

        if (DouyinResolver.isDouyinInput(trimmed)) {
            return DouyinResolver.resolve(trimmed);
        }

        throw new IOException("不支持的链接：目前支持 抖音（v.douyin.com / 视频ID）和 B站（BV / av / b23.tv）");
    }
}
