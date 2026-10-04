package com.nexus.auth.ApiKeyAuthenticator;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.apikey.credential.ApiKeyCredentialGenerator;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.exception.ApiKeyForbiddenException;
import com.nexus.auth.exception.InvalidApiKeyCredentialException;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@AllArgsConstructor
@Component
public class ApiKeyAuthenticator {
    private final ApiKeyMapper apiKeyMapper;
    private final ApplicationMapper applicationMapper;
    private final UserMapper userMapper;
    private final ApiKeyCredentialGenerator credentialGenerator;

    private static final Pattern FULL_KEY_PATTERN = Pattern.compile(
            "^nxk_v1_([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})_([A-Za-z0-9_-]{43})$");

    // 封装一个记录解析结果的内部record
    private record ParsedKey(String publicId, String secret) {}

    /**
     * 解析 fullKey，提取 publicId 和 secret
     * @param fullKey 完整apikey，例如 nxk_v1_abc_123secret
     * @return 解析成功返回ParsedKey；格式不对返回null
     */
    private ParsedKey parse(String fullKey) {
        if (fullKey == null || fullKey.isBlank()) {
            return null;
        }
        Matcher matcher = FULL_KEY_PATTERN.matcher(fullKey);
        if (!matcher.matches()) {
            return null;
        }
        String publicId = matcher.group(1);
        String secret = matcher.group(2);
        return new ParsedKey(publicId, secret);
    }

    public ApiKeyIdentity authenticate(String fullKey){
        ParsedKey parsed = parse(fullKey);
        // 格式解析失败 → 认证失败
        if (parsed == null) {
            throw new InvalidApiKeyCredentialException();
        }
        String publicId = parsed.publicId();
        String secret = parsed.secret();

        // 1. 根据publicId查询ApiKey记录
        ApiKey apiKey = apiKeyMapper.selectOne(
                Wrappers.<ApiKey>lambdaQuery()
                        .eq(ApiKey::getPublicId, publicId)
        );
        if (apiKey == null || !credentialGenerator.matchesSecret(secret, apiKey.getSecretHash())) {
            throw new InvalidApiKeyCredentialException();
        }
        // Secret 验证成功后再判断状态，避免错误 Secret 探测禁用状态。
        if (Integer.valueOf(1).equals(apiKey.getIsDeleted())) {
            throw new InvalidApiKeyCredentialException();
        }
        Application application = applicationMapper.selectById(apiKey.getApplicationId());
        if (application == null || Integer.valueOf(1).equals(application.getIsDeleted())) {
            throw new InvalidApiKeyCredentialException();
        }
        // 用户状态只读取 users 的权威记录，不复制到 Application 或 Key。
        User owner = userMapper.selectById(application.getOwnerUserId());
        if (owner == null || Integer.valueOf(1).equals(owner.getIsDeleted())) {
            throw new InvalidApiKeyCredentialException();
        }
        // 所有删除状态优先按无效凭证处理；仅在身份仍有效时报告禁用。
        if (Integer.valueOf(1).equals(apiKey.getStatus())
                || Integer.valueOf(1).equals(application.getStatus())
                || Integer.valueOf(1).equals(owner.getStatus())) {
            throw new ApiKeyForbiddenException();
        }
        return new ApiKeyIdentity(apiKey.getId(), apiKey.getApplicationId());
    }
}
