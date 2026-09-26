package com.nexus.application.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.nexus.application.dto.CreateApplicationRequest;
import com.nexus.application.dto.CreateApplicationResponse;
import com.nexus.application.entity.Application;
import com.nexus.application.exception.ApplicationNameAlreadyExistsException;
import com.nexus.application.mapper.ApplicationMapper;
import lombok.AllArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

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
}
