package com.nexus.webExtract.dto;

/** contentHtml 保留正文与图片顺序；textContent 便于机器端直接处理文本。 */
public record WebExtractResponse(String title, String textContent, String contentHtml) {
}
