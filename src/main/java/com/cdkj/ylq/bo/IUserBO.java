package com.cdkj.ylq.bo;

import com.cdkj.ylq.domain.User;
import com.cdkj.ylq.enums.EUserKind;

/**
 * @author: xieyj 
 * @since: 2016年5月30日 上午9:28:13 
 * @history:
 */
public interface IUserBO {

    public void checkTradePwd(String userId, String tradePwd);

    public User getRemoteUser(String userId);

    /**
     * 根据手机号获取用户编号
     * @param mobile
     * @param kind
     * @param systemCode
     * @return 
     * @create: 2016年12月28日 上午10:09:53 xieyj
     * @history:
     */
    public String isUserExist(String mobile, EUserKind kind, String systemCode);

    public String doSaveBUser(String mobile, String userReferee,
            String updater, String systemCode, String companyCode);

    public String doSavePartnerUser(String mobile, String userReferee,
            String updater, String systemCode, String companyCode);

    /**
     * 根据systemCode获取系统userId
     * @param systemCode
     * @return 
     * @create: 2017年3月23日 下午2:03:03 myb858
     * @history:
     */
    public String getSystemUser(String systemCode);
}
