package com.nexus.apikey.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nexus.apikey.credential.ApiKeyCredentialGenerator;
import com.nexus.apikey.credential.GeneratedApiKey;
import com.nexus.apikey.dto.*;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.exception.ApiKeyNameAlreadyExistsException;
import com.nexus.apikey.exception.ApiKeyNotFoundException;
import com.nexus.apikey.exception.InvalidApiKeyDeleteException;
import com.nexus.apikey.exception.InvalidApiKeyUpdateException;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.exception.ApplicationDisabledException;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.shortlink.entity.ShortLink;
import com.nexus.shortlink.mapper.ShortLinkMapper;
import com.nexus.common.web.ListPage;
import com.nexus.common.web.ListPageParameters;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ApiKeyService {
    private final ApiKeyMapper apiKeyMapper;
    private final ApplicationMapper applicationMapper;
    private final ShortLinkMapper shortLinkMapper;
    private final ApiKeyCredentialGenerator credentialGenerator;

    @Transactional
    public CreateApiKeyResponse createMyApiKey(Long userId, CreateApiKeyRequest createApiKeyRequest){
        //校验application
        Application application = applicationMapper.selectOne(
                Wrappers.<Application>lambdaQuery()
                        .eq(Application::getId, createApiKeyRequest.applicationId())
                        .eq(Application::getOwnerUserId, userId)
                        .eq(Application::getIsDeleted, 0)
                        .last("FOR UPDATE")
        );
        if (application == null) {
            throw new ApplicationNotFoundException();
        }
        if (Integer.valueOf(1).equals(application.getStatus())) {
            throw new ApplicationDisabledException();
        }
        //生成密钥
        GeneratedApiKey generatedApiKey = credentialGenerator.generate();
        LocalDateTime now = LocalDateTime.now();

        ApiKey apiKey = new ApiKey();
        apiKey.setApplicationId(createApiKeyRequest.applicationId());
        apiKey.setName(createApiKeyRequest.name().strip());
        apiKey.setPublicId(generatedApiKey.publicId());
        apiKey.setKeyPreview(generatedApiKey.keyPreview());
        apiKey.setSecretHash(generatedApiKey.secretHash());
        apiKey.setStatus(0);
        apiKey.setIsDeleted(0);
        apiKey.setCreatedAt(now);
        apiKey.setUpdatedAt(now);

        try {
            apiKeyMapper.insert(apiKey);
        } catch (DuplicateKeyException exception) {
            throw new ApiKeyNameAlreadyExistsException(exception);
        }

        return new CreateApiKeyResponse(
                apiKey.getId(),
                apiKey.getApplicationId(),
                apiKey.getName(),
                generatedApiKey.publicId(),
                generatedApiKey.keyPreview(),
                generatedApiKey.plaintextKey(),
                apiKey.getStatus(),
                apiKey.getCreatedAt()
        );
    }

    public ListPage<ApiKeyResponse> listMyApiKeys(Long userId, Long applicationId, long current, long size,
                                                  Long apiKeyId){
        ListPageParameters.validate(current, size);
        //校验application
        Application application = applicationMapper.selectOne(
                Wrappers.<Application>lambdaQuery()
                        .eq(Application::getId, applicationId)
                        .eq(Application::getOwnerUserId, userId)
                        .eq(Application::getIsDeleted, 0)
        );
        if (application == null) {
            throw new ApplicationNotFoundException();
        }
        Page<ApiKey> keys = apiKeyMapper.selectPage(new Page<>(current, size),
                Wrappers.<ApiKey>lambdaQuery()
                        .eq(ApiKey::getApplicationId, applicationId)
                        .eq(ApiKey::getIsDeleted, 0)
                        .eq(apiKeyId != null, ApiKey::getId, apiKeyId)
                        .orderByDesc(ApiKey::getCreatedAt)
                        .orderByDesc(ApiKey::getId)
        );

        return ListPage.map(keys, key -> new ApiKeyResponse(
                        key.getId(),
                        key.getApplicationId(),
                        key.getName(),
                        key.getPublicId(),
                        key.getKeyPreview(),
                        key.getStatus(),
                        key.getCreatedAt(),
                        key.getUpdatedAt()
                ));
    }

    @Transactional
    public ApiKeyResponse updateMyApiKey(Long userId ,UpdateApiKeyRequest updateApiKeyRequest){
        int affectedRows;
        //校验请求
        if (updateApiKeyRequest.apiKeyId() == null || updateApiKeyRequest.apiKeyId() <= 0
        ||updateApiKeyRequest.applicationId() == null || updateApiKeyRequest.applicationId() <= 0) {
            throw new InvalidApiKeyUpdateException("应用ID 或 密钥ID 必须为正数");
        }

        String name = updateApiKeyRequest.name();
        Integer status = updateApiKeyRequest.status();
        if (name == null && status == null) {
            throw new InvalidApiKeyUpdateException("至少提供一个要更新的字段");
        }
        if (name != null) {
            name = name.strip();
            if (name.isEmpty() || name.length() > 64) {
                throw new InvalidApiKeyUpdateException("密钥名称不能为空且不能超过 64 个字符");
            }
        }
        if (status != null && status != 0 && status != 1) {
            throw new InvalidApiKeyUpdateException("密钥状态只能是 0 或 1");
        }

        //校验application
        Application application = applicationMapper.selectOne(
                Wrappers.<Application>lambdaQuery()
                        .eq(Application::getId, updateApiKeyRequest.applicationId())
                        .eq(Application::getOwnerUserId, userId)
                        .eq(Application::getIsDeleted, 0)
                        .last("FOR UPDATE")
        );
        if (application == null) {
            throw new ApplicationNotFoundException();
        }
        LambdaUpdateWrapper<ApiKey> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
        lambdaUpdateWrapper
                .eq(ApiKey::getId, updateApiKeyRequest.apiKeyId())
                .eq(ApiKey::getApplicationId, updateApiKeyRequest.applicationId())
                .eq(ApiKey::getIsDeleted, 0)
                .set(name != null, ApiKey::getName, name)
                .set(status!= null, ApiKey::getStatus, status)
                .set(ApiKey::getUpdatedAt, LocalDateTime.now());
        try {
            affectedRows = apiKeyMapper.update(null, lambdaUpdateWrapper);
        }catch (DuplicateKeyException exception){
            throw new ApiKeyNameAlreadyExistsException(exception);
        }
        if(affectedRows > 0){
            ApiKey apiKey = apiKeyMapper.selectById(updateApiKeyRequest.apiKeyId());
            return new ApiKeyResponse(
                    apiKey.getId(),
                    apiKey.getApplicationId(),
                    apiKey.getName(),
                    apiKey.getPublicId(),
                    apiKey.getKeyPreview(),
                    apiKey.getStatus(),
                    apiKey.getCreatedAt(),
                    apiKey.getUpdatedAt()
            );
        }else {
            throw new ApiKeyNotFoundException();
        }
    }

    @Transactional
    public void deleteMyApiKey(Long userId ,DeleteApiKeyRequest deleteApiKeyRequest){
        int affectedRows;
        //校验请求
        if (deleteApiKeyRequest.apiKeyId() == null || deleteApiKeyRequest.apiKeyId() <= 0
                ||deleteApiKeyRequest.applicationId() == null || deleteApiKeyRequest.applicationId() <= 0) {
            throw new InvalidApiKeyDeleteException("应用ID 或 密钥ID 必须为正数");
        }
        //校验application
        Application application = applicationMapper.selectOne(
                Wrappers.<Application>lambdaQuery()
                        .eq(Application::getId, deleteApiKeyRequest.applicationId())
                        .eq(Application::getOwnerUserId, userId)
                        .eq(Application::getIsDeleted, 0)
                        .last("FOR UPDATE")
        );
        if (application == null) {
            throw new ApplicationNotFoundException();
        }
        LocalDateTime deletedAt = LocalDateTime.now();
        LambdaUpdateWrapper<ApiKey> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
        lambdaUpdateWrapper
                .eq(ApiKey::getId, deleteApiKeyRequest.apiKeyId())
                .eq(ApiKey::getApplicationId, deleteApiKeyRequest.applicationId())
                .eq(ApiKey::getIsDeleted, 0)
                .set(ApiKey::getIsDeleted, 1)
                .set(ApiKey::getDeletedAt, deletedAt);
        affectedRows = apiKeyMapper.update(null, lambdaUpdateWrapper);
        if(affectedRows > 0){
            shortLinkMapper.update(null, Wrappers.<ShortLink>lambdaUpdate()
                    .eq(ShortLink::getApiKeyId, deleteApiKeyRequest.apiKeyId())
                    .eq(ShortLink::getIsDeleted, 0)
                    .set(ShortLink::getIsDeleted, 1)
                    .set(ShortLink::getDeletedAt, deletedAt)
                    .set(ShortLink::getUpdatedAt, deletedAt));
            return ;
        }else {
            throw new ApiKeyNotFoundException();
        }
    }

}
