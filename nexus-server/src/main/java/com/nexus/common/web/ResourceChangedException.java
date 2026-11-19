package com.nexus.common.web;

/** The resource is owned, but the client edited an older representation. */
public class ResourceChangedException extends RuntimeException {
    public ResourceChangedException() { super("资料已被修改，请刷新核对后重新操作"); }
}
