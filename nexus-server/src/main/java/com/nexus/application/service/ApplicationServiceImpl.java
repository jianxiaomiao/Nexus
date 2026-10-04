package com.nexus.application.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.nexus.application.dto.*;
import com.nexus.application.entity.Application;
import com.nexus.application.exception.ApplicationNameAlreadyExistsException;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.exception.InvalidApplicationIdException;
import com.nexus.application.exception.InvalidApplicationUpdateException;
import com.nexus.application.mapper.ApplicationMapper;
import lombok.AllArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@AllArgsConstructor
public class ApplicationServiceImpl extends ServiceImpl<ApplicationMapper, Application> implements ApplicationService{

    private final ApplicationMapper applicationMapper;

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

    public List<ApplicationResponse> listMyApplications(Long userId){
        List<Application> applications = applicationMapper.selectList(
                Wrappers.<Application>lambdaQuery()
                        .eq(Application::getOwnerUserId, userId)
                        .eq(Application::getIsDeleted, 0)
                        .orderByDesc(Application::getCreatedAt)
                        .orderByDesc(Application::getId)
        );
        return applications.stream()
                .map(app -> new ApplicationResponse(
                        app.getId(),
                        app.getName(),
                        app.getStatus(),
                        app.getCreatedAt(),
                        app.getUpdatedAt()))
                .toList();
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

    public void deleteMyApplication(Long userId, Long appId){
        if (appId == null || appId <= 0) {
            throw new InvalidApplicationIdException();
        }
        int affetRow = 0;
        LambdaUpdateWrapper<Application> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
        lambdaUpdateWrapper
                .eq(Application::getId,appId)
                .eq(Application::getOwnerUserId,userId)
                .eq(Application::getIsDeleted,0)
                .set(Application::getIsDeleted,1)
                .set(Application::getDeletedAt,LocalDateTime.now());

        affetRow =applicationMapper.update(null, lambdaUpdateWrapper);
        if(affetRow>0){
            return ;
        }else {
            throw new ApplicationNotFoundException();
        }
    }


}
