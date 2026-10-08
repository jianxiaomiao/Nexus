package com.nexus.application.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.dto.*;
import com.nexus.application.entity.Application;
import com.nexus.application.exception.ApplicationNameAlreadyExistsException;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.exception.InvalidApplicationIdException;
import com.nexus.application.exception.InvalidApplicationUpdateException;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.shortlink.entity.ShortLink;
import com.nexus.shortlink.mapper.ShortLinkMapper;
import com.nexus.common.web.ListPage;
import com.nexus.common.web.ListPageParameters;
import lombok.AllArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@AllArgsConstructor
public class ApplicationService {

    private final ApplicationMapper applicationMapper;

    private final ApiKeyMapper apiKeyMapper;
    private final ShortLinkMapper shortLinkMapper;

    public CreateApplicationResponse createApplication(Long userId, CreateApplicationRequest createApplicationRequest){
        //获取用户id
        //new 一个application（包含name和owner——userId）
        Application application = new Application();
        application.setOwnerUserId(userId);
        application.setName(createApplicationRequest.name().strip());
        //try catch处理错误：重复名称的问题
        try {
            applicationMapper.insert(application);
        }catch (DuplicateKeyException exception){
            throw new ApplicationNameAlreadyExistsException(exception);
        }
        return new CreateApplicationResponse(application.getId(),application.getName());
    }

    public ListPage<ApplicationResponse> listMyApplications(Long userId, long current, long size, Long applicationId){
        ListPageParameters.validate(current, size);
        Page<Application> applications = applicationMapper.selectPage(new Page<>(current, size),
                Wrappers.<Application>lambdaQuery()
                        .eq(Application::getOwnerUserId, userId)
                        .eq(Application::getIsDeleted, 0)
                        .eq(applicationId != null, Application::getId, applicationId)
                        .orderByDesc(Application::getCreatedAt)
                        .orderByDesc(Application::getId)
        );
        return ListPage.map(applications, app -> new ApplicationResponse(
                        app.getId(),
                        app.getName(),
                        app.getStatus(),
                        app.getCreatedAt(),
                        app.getUpdatedAt()));
    }

    public ApplicationResponse updateMyApplication(Long userId, UpdateApplicationRequest updateApplicationRequest){
        int affetRow = 0;
        if (updateApplicationRequest.id() == null || updateApplicationRequest.id() <= 0) {
            throw new InvalidApplicationUpdateException("应用 ID 必须为正数");
        }
        String name = updateApplicationRequest.name();
        Integer status = updateApplicationRequest.status();
        if (name == null && status == null) {
            throw new InvalidApplicationUpdateException("至少提供一个要更新的字段");
        }
        if (name != null) {
            name = name.strip();
            if (name.isEmpty() || name.length() > 64) {
                throw new InvalidApplicationUpdateException("应用名称不能为空且不能超过 64 个字符");
            }
        }
        if (status != null && status != 0 && status != 1) {
            throw new InvalidApplicationUpdateException("应用状态只能是 0 或 1");
        }

        LambdaUpdateWrapper<Application> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
        lambdaUpdateWrapper
                .eq(Application::getId,updateApplicationRequest.id())
                .eq(Application::getOwnerUserId,userId)
                .eq(Application::getIsDeleted,0)
                .set(name != null, Application::getName, name)
                .set(status != null, Application::getStatus, status)
                .set(Application::getUpdatedAt, LocalDateTime.now());
        try {
            affetRow =applicationMapper.update(null, lambdaUpdateWrapper);
        }catch (DuplicateKeyException exception){
            throw new ApplicationNameAlreadyExistsException(exception);
        }

        if(affetRow>0){
            Application application = applicationMapper.selectById(updateApplicationRequest.id());
            return new ApplicationResponse(
                    application.getId(),
                    application.getName(),
                    application.getStatus(),
                    application.getCreatedAt(),
                    application.getUpdatedAt()
            );
        }else {
            throw new ApplicationNotFoundException();
        }
    }

    @Transactional
    public void deleteMyApplication(Long userId, Long appId){
        if (appId == null || appId <= 0) {
            throw new InvalidApplicationIdException();
        }
        LocalDateTime deletedAt = LocalDateTime.now();
        LambdaUpdateWrapper<Application> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
        lambdaUpdateWrapper
                .eq(Application::getId,appId)
                .eq(Application::getOwnerUserId,userId)
                .eq(Application::getIsDeleted,0)
                .set(Application::getIsDeleted,1)
                .set(Application::getDeletedAt,deletedAt);

        int affectedRows = applicationMapper.update(null, lambdaUpdateWrapper);
        if (affectedRows == 0) {
            throw new ApplicationNotFoundException();
        }

        List<Long> keyIds = apiKeyMapper.selectList(Wrappers.<ApiKey>lambdaQuery()
                        .eq(ApiKey::getApplicationId, appId))
                .stream().map(ApiKey::getId).toList();

        apiKeyMapper.update(null, Wrappers.<ApiKey>lambdaUpdate()
                .eq(ApiKey::getApplicationId, appId)
                .eq(ApiKey::getIsDeleted, 0)
                .set(ApiKey::getIsDeleted, 1)
                .set(ApiKey::getDeletedAt, deletedAt));
        if (!keyIds.isEmpty()) {
            shortLinkMapper.update(null, Wrappers.<ShortLink>lambdaUpdate()
                    .in(ShortLink::getApiKeyId, keyIds)
                    .eq(ShortLink::getIsDeleted, 0)
                    .set(ShortLink::getIsDeleted, 1)
                    .set(ShortLink::getDeletedAt, deletedAt)
                    .set(ShortLink::getUpdatedAt, deletedAt));
        }
    }


}
