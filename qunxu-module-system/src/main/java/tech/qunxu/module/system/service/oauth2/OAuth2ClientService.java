package tech.qunxu.module.system.service.oauth2;

import com.baomidou.dynamic.datasource.annotation.DS;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.exception.util.ServiceExceptionUtil;
import tech.qunxu.module.system.dal.dataobject.oauth2.OAuth2ClientDO;
import tech.qunxu.module.system.dal.mysql.oauth2.OAuth2ClientMapper;
import tech.qunxu.module.system.enums.ErrorCodeConstants;

/**
 * OAuth2 客户端服务。
 */
@Service
@RequiredArgsConstructor
public class OAuth2ClientService {

    private final OAuth2ClientMapper oauth2ClientMapper;

    /**
     * 根据客户端编号获取客户端
     */
    @DS("slave")
    public OAuth2ClientDO getOAuth2ClientByClientId(String clientId) {
        OAuth2ClientDO client = oauth2ClientMapper.selectOne(
                new LambdaQueryWrapper<OAuth2ClientDO>().eq(OAuth2ClientDO::getClientId, clientId));
        if (client == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.OAUTH2_CLIENT_NOT_EXISTS);
        }
        return client;
    }

    /**
     * 校验客户端密钥
     */
    public void validClientSecret(String secret, OAuth2ClientDO client) {
        // 客户端未配置密钥，则不校验
        if (client.getSecret() == null || client.getSecret().isEmpty()) {
            return;
        }
        if (secret == null || !secret.equals(client.getSecret())) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.OAUTH2_CLIENT_SECRET_ERROR);
        }
    }

}
