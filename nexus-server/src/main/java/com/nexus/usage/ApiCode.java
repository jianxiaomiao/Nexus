package com.nexus.usage;

public enum ApiCode {
    // 短链接口
    SHORTLINK_CREATE("shortlink.create", "创建短链接"),
    SHORTLINK_LIST("shortlink.list", "查询短链接"),
    SHORTLINK_UPDATE("shortlink.update", "更新短链接"),
    SHORTLINK_DELETE("shortlink.delete", "删除短链接"),

    // hash工具接口
    UTILS_HASH("utils.hash", "哈希计算工具"),
    WEB_EXTRACT("web.extract", "网页正文提取"),
    // 其他接口继续在这里扩展
    UUID_GENERATE("uuid.generate", "生成UUID");

    private final String code;
    private final String desc;

    ApiCode(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
