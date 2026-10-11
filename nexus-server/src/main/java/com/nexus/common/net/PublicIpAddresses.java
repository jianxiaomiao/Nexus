package com.nexus.common.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/** 网页提取与截图共享地址判断；业务域名白名单仍由各模块决定。 */
public final class PublicIpAddresses {
    private PublicIpAddresses() { }

    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = Byte.toUnsignedInt(bytes[0]);
            int b = Byte.toUnsignedInt(bytes[1]);
            int c = Byte.toUnsignedInt(bytes[2]);
            return a > 0 && a < 224
                    && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 192 && b == 0 && c == 0)
                    && !(a == 192 && b == 0 && c == 2)
                    && !(a == 192 && b == 88 && c == 99)
                    && !(a == 198 && (b == 18 || b == 19))
                    && !(a == 198 && b == 51 && c == 100)
                    && !(a == 203 && b == 0 && c == 113);
        }
        return address instanceof Inet6Address
                && (Byte.toUnsignedInt(bytes[0]) & 0xe0) == 0x20
                && !(Byte.toUnsignedInt(bytes[0]) == 0x20 && Byte.toUnsignedInt(bytes[1]) == 0x01
                && Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8);
    }
}
