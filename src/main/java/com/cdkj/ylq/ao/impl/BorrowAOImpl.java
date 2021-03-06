package com.cdkj.ylq.ao.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cdkj.ylq.ao.IBorrowAO;
import com.cdkj.ylq.bo.IAccountBO;
import com.cdkj.ylq.bo.IApplyBO;
import com.cdkj.ylq.bo.IBorrowBO;
import com.cdkj.ylq.bo.ICertificationBO;
import com.cdkj.ylq.bo.IProductBO;
import com.cdkj.ylq.bo.ISmsOutBO;
import com.cdkj.ylq.bo.IUserBO;
import com.cdkj.ylq.bo.IUserCouponBO;
import com.cdkj.ylq.bo.base.Paginable;
import com.cdkj.ylq.common.AmountUtil;
import com.cdkj.ylq.common.JsonUtil;
import com.cdkj.ylq.core.CalculationUtil;
import com.cdkj.ylq.core.OrderNoGenerater;
import com.cdkj.ylq.domain.Borrow;
import com.cdkj.ylq.domain.Certification;
import com.cdkj.ylq.domain.InfoAmount;
import com.cdkj.ylq.domain.InfoBankcard;
import com.cdkj.ylq.domain.Product;
import com.cdkj.ylq.domain.User;
import com.cdkj.ylq.domain.UserCoupon;
import com.cdkj.ylq.enums.EApplyStatus;
import com.cdkj.ylq.enums.EBizType;
import com.cdkj.ylq.enums.EBoolean;
import com.cdkj.ylq.enums.EBorrowStatus;
import com.cdkj.ylq.enums.ECertiKey;
import com.cdkj.ylq.enums.ECertificationStatus;
import com.cdkj.ylq.enums.EGeneratePrefix;
import com.cdkj.ylq.enums.EPayType;
import com.cdkj.ylq.enums.ESysUser;
import com.cdkj.ylq.enums.EUserCouponStatus;
import com.cdkj.ylq.exception.BizException;

@Service
public class BorrowAOImpl implements IBorrowAO {

    protected static final Logger logger = LoggerFactory
        .getLogger(IBorrowAO.class);

    @Autowired
    private IBorrowBO borrowBO;

    @Autowired
    private IApplyBO applyBO;

    @Autowired
    private ICertificationBO certificationBO;

    @Autowired
    private IUserCouponBO userCouponBO;

    @Autowired
    private IProductBO productBO;

    @Autowired
    private IUserBO userBO;

    @Autowired
    private IAccountBO accountBO;

    @Autowired
    private ISmsOutBO smsOutBO;

    @Override
    @Transactional
    public String borrow(String userId, Long couponId) {
        User user = userBO.getRemoteUser(userId);
        if (EBoolean.YES.getCode().equals(user.getBlacklistFlag())) {
            throw new BizException("xn000000", "由于您逾期未还款，已被平台拉入黑名单，请联系平台进行处理！");
        }
        // 授信额度信息校验
        Certification certification = certificationBO.getCertification(userId,
            ECertiKey.INFO_AMOUNT);
        if (certification == null) {
            throw new BizException("623070", "您还没有额度，请先选择产品进行申请");
        }
        InfoAmount infoAmount = JsonUtil.json2Bean(certification.getResult(),
            InfoAmount.class);
        if (infoAmount.getSxAmount() == 0) {
            throw new BizException("623070", "您还没有额度，请先选择产品进行申请");
        }
        if (StringUtils.isBlank(certification.getRef())) {
            throw new BizException("623070", "您还没有额度，请先选择产品进行申请");
        }
        if (ECertificationStatus.INVALID.getCode().equals(
            certification.getFlag())) {
            throw new BizException("623070", "您的额度已失效，请选择产品重新申请");
        }
        // 是否已经有借款
        if (borrowBO.getCurrentBorrow(userId) != null) {
            throw new BizException("623070", "当前已有借款");
        }
        // 产品
        Product product = productBO.getProduct(certification.getRef());

        String code = OrderNoGenerater.generateM(EGeneratePrefix.BORROW
            .getCode());
        Date now = new Date();
        // 优惠金额
        Long yhAmount = 0L;
        if (couponId != null) {
            UserCoupon userCoupon = userCouponBO.getUserCoupon(couponId);
            if (!EUserCouponStatus.TO_USE.getCode().equals(
                userCoupon.getStatus())) {
                throw new BizException("623070", "优惠券已不可使用");
            }
            if (infoAmount.getSxAmount() < userCoupon.getStartAmount()) {
                throw new BizException("623070", "不可使用该优惠券");
            }
            yhAmount = userCoupon.getAmount();
            userCouponBO.use(userCoupon, code);
        }
        // 借款总额
        Long borrowAmount = infoAmount.getSxAmount();
        // 利息
        Long lxAmount = AmountUtil.eraseLiUp(AmountUtil.mul(borrowAmount,
            product.getLxRate()));
        // 快速信审费
        Long xsAmount = AmountUtil.eraseLiUp(AmountUtil.mul(borrowAmount,
            product.getXsRate()));
        // 账户管理费
        Long glAmount = AmountUtil.eraseLiUp(AmountUtil.mul(borrowAmount,
            product.getGlRate()));
        // 服务费
        Long fwAmount = AmountUtil.eraseLiUp(AmountUtil.mul(borrowAmount,
            product.getFwRate()));
        // 应还金额
        Long totalAmount = borrowAmount;

        Borrow borrow = new Borrow();

        borrow.setCode(code);
        borrow.setApplyUser(userId);
        borrow.setSignDatetime(now);
        borrow.setAmount(borrowAmount);
        borrow.setLevel(product.getLevel());
        borrow.setDuration(product.getDuration());

        borrow.setLxAmount(lxAmount);
        borrow.setXsAmount(xsAmount);
        borrow.setGlAmount(glAmount);
        borrow.setFwAmount(fwAmount);
        borrow.setYhAmount(yhAmount);

        borrow.setRate1(product.getYqRate1());
        borrow.setRate2(product.getYqRate2());
        borrow.setYqlxAmount(0L);
        borrow.setYqDays(0);
        borrow.setTotalAmount(totalAmount);

        borrow.setRealHkAmount(0L);
        borrow.setStatus(EBorrowStatus.TO_APPROVE.getCode());
        borrow.setUpdater(userId);
        borrow.setUpdateDatetime(now);
        borrow.setRemark("新申请借款");

        borrowBO.saveBorrow(borrow);

        // 更新申请单状态
        applyBO.refreshCurrentApplyStatus(userId, EApplyStatus.TO_LOAN);

        // 额度减去
        certificationBO.refreshSxAmount(borrow.getApplyUser(),
            -borrow.getAmount());

        return code;
    }

    @Override
    public Paginable<Borrow> queryBorrowPage(int start, int limit,
            Borrow condition) {
        Paginable<Borrow> results = borrowBO.getPaginable(start, limit,
            condition);
        List<Borrow> borrowList = results.getList();
        for (Borrow borrow : borrowList) {
            borrow.setUser(userBO.getRemoteUser(borrow.getApplyUser()));
            Certification certification = certificationBO.getCertification(
                borrow.getApplyUser(), ECertiKey.INFO_BANKCARD);
            if (certification != null
                    && StringUtils.isNotBlank(certification.getResult())) {
                borrow.setInfoBankcard(JsonUtil.json2Bean(
                    certification.getResult(), InfoBankcard.class));
            }
        }
        return results;
    }

    @Override
    public Paginable<Borrow> queryMyBorrowPage(int start, int limit,
            Borrow condition) {
        return borrowBO.getPaginable(start, limit, condition);
    }

    @Override
    public Borrow getBorrow(String code) {
        Borrow borrow = borrowBO.getBorrow(code);
        borrow.setUser(userBO.getRemoteUser(borrow.getApplyUser()));
        Certification certification = certificationBO.getCertification(
            borrow.getApplyUser(), ECertiKey.INFO_BANKCARD);
        if (certification != null
                && StringUtils.isNotBlank(certification.getResult())) {
            borrow.setInfoBankcard(JsonUtil.json2Bean(
                certification.getResult(), InfoBankcard.class));
        }
        return borrow;
    }

    @Override
    @Transactional
    public void doApprove(String code, String approveResult, String approver,
            String approveNote) {
        Borrow borrow = borrowBO.getBorrow(code);
        if (!EBorrowStatus.TO_APPROVE.getCode().equals(borrow.getStatus())) {
            throw new BizException("xn623000", "该申请记录不处于待审核状态");
        }
        String status = null;
        if (EBoolean.YES.getCode().equals(approveResult)) {
            status = EBorrowStatus.APPROVE_YES.getCode();
        } else {
            status = EBorrowStatus.APPROVE_NO.getCode();
            // 返回优惠券
            userCouponBO.useCancel(borrow.getCode());
            // 更新申请单状态
            Certification certification = certificationBO.getCertification(
                borrow.getApplyUser(), ECertiKey.INFO_AMOUNT);
            if (ECertificationStatus.CERTI_YES.getCode().equals(
                certification.getFlag())) {
                applyBO.refreshCurrentApplyStatus(borrow.getApplyUser(),
                    EApplyStatus.APPROVE_YES);
            } else {
                applyBO.refreshCurrentApplyStatus(borrow.getApplyUser(),
                    EApplyStatus.CANCEL);
            }
            // 返还额度
            certificationBO.refreshSxAmount(borrow.getApplyUser(),
                borrow.getAmount());

            smsOutBO.sentContent(borrow.getApplyUser(), "很抱歉，您的"
                    + CalculationUtil.diviUp(borrow.getAmount())
                    + "借款未能审核通过，合同编号为" + borrow.getCode() + "，原因："
                    + approveNote + "。");
        }
        borrowBO.doApprove(borrow, status, approver, approveNote);
    }

    @Override
    @Transactional
    public void loan(String code, String updater, String remark) {
        Borrow borrow = borrowBO.getBorrow(code);
        if (!EBorrowStatus.APPROVE_YES.getCode().equals(borrow.getStatus())) {
            throw new BizException("623071", "借款不处于待放款状态");
        }
        borrowBO.loan(borrow, updater, remark);

        // 更新申请单状态
        applyBO.refreshCurrentApplyStatus(borrow.getApplyUser(),
            EApplyStatus.LOANING);

        smsOutBO.sentContent(borrow.getApplyUser(),
            "恭喜您，您的" + CalculationUtil.diviUp(borrow.getAmount())
                    + "借款已经成功放款，合同编号为" + borrow.getCode() + "，详情查看请登录APP。");
    }

    @Override
    public Object repay(String code, String payType) {
        Borrow borrow = borrowBO.getBorrow(code);
        if (!EBorrowStatus.LOANING.getCode().equals(borrow.getStatus())
                && !EBorrowStatus.OVERDUE.getCode().equals(borrow.getStatus())) {
            throw new BizException("623071", "借款不处于待还款状态");
        }
        if (EPayType.ALIPAY.getCode().equals(payType)) {
            return doRepayAlipay(borrow);
        } else if (EPayType.WEIXIN_APP.getCode().equals(payType)) {
            return doRepayWechat(borrow);
        } else {
            throw new BizException("623071", "暂不支持此支付方式");
        }
    }

    private Object doRepayWechat(Borrow borrow) {
        Long rmbAmount = borrow.getTotalAmount();
        User user = userBO.getRemoteUser(borrow.getApplyUser());
        String payGroup = borrowBO.addPayGroup(borrow.getCode());
        return accountBO.doWeiXinPayRemote(user.getUserId(),
            ESysUser.SYS_USER_YLQ.getCode(), payGroup, borrow.getCode(),
            EBizType.YLQ_REPAY, EBizType.YLQ_REPAY.getValue() + "-微信",
            rmbAmount);
    }

    private Object doRepayAlipay(Borrow borrow) {
        Long rmbAmount = borrow.getTotalAmount();
        User user = userBO.getRemoteUser(borrow.getApplyUser());
        String payGroup = borrowBO.addPayGroup(borrow.getCode());
        return accountBO.doAlipayRemote(user.getUserId(),
            ESysUser.SYS_USER_YLQ.getCode(), payGroup, borrow.getCode(),
            EBizType.YLQ_REPAY, EBizType.YLQ_REPAY.getValue() + "-支付宝",
            rmbAmount);
    }

    @Override
    @Transactional
    public String repaySuccess(String payGroup, String payType, String payCode,
            Long amount) {
        String userId = null;
        List<Borrow> borrowList = borrowBO.queryBorrowListByPayGroup(payGroup);
        if (CollectionUtils.isEmpty(borrowList)) {
            throw new BizException("XN000000", "找不到对应的借款记录");
        }
        Borrow borrow = borrowList.get(0);
        if (EBorrowStatus.LOANING.getCode().equals(borrow.getStatus())
                || EBorrowStatus.OVERDUE.getCode().equals(borrow.getStatus())) {
            // 更新订单支付金额
            borrowBO.repaySuccess(borrow, amount, payCode, payType);
            // 更新申请单状态
            applyBO.refreshCurrentApplyStatus(borrow.getApplyUser(),
                EApplyStatus.REPAY);
            // 额度重置为0
            certificationBO.resetSxAmount(borrow.getApplyUser());
            userId = borrow.getApplyUser();
            smsOutBO.sentContent(borrow.getApplyUser(),
                "您的" + CalculationUtil.diviUp(borrow.getAmount())
                        + "借款已经成功还款，合同编号为" + borrow.getCode() + "，详情查看请登录APP。");
        } else {
            logger.info("订单号：" + borrow.getCode() + "已支付，重复回调");
        }
        return userId;
    }

    @Override
    @Transactional
    public void confirmBad(String code, String updater, String remark) {
        Borrow borrow = borrowBO.getBorrow(code);
        if (!EBorrowStatus.OVERDUE.getCode().equals(borrow.getStatus())) {
            throw new BizException("623073", "借款不处于逾期状态");
        }
        borrow.setStatus(EBorrowStatus.BAD.getCode());
        borrow.setUpdater(updater);
        borrow.setUpdateDatetime(new Date());
        borrow.setRemark(remark);
        borrowBO.confirmBad(borrow);

        // 更新申请单状态
        applyBO.refreshCurrentApplyStatus(borrow.getApplyUser(),
            EApplyStatus.BAD);

        // 额度重置为0
        certificationBO.resetSxAmount(borrow.getApplyUser());

        // 将用户拉入黑名单
        userBO.addBlacklist(borrow.getApplyUser(), "bad_debt", updater,
            "借钱不还，已确认坏账");

    }

    @Override
    public void doCheckOverdueDaily() {
        logger.info("***************开始扫描逾期借款***************");
        Borrow condition = new Borrow();
        List<String> statusList = new ArrayList<String>();
        statusList.add(EBorrowStatus.LOANING.getCode());
        statusList.add(EBorrowStatus.OVERDUE.getCode());
        condition.setStatusList(statusList);
        condition.setCurDatetime(new Date());
        List<Borrow> borrowList = borrowBO.queryBorrowList(condition);
        if (CollectionUtils.isNotEmpty(borrowList)) {
            for (Borrow borrow : borrowList) {
                overdue(borrow);
            }
        }
        logger.info("***************结束扫描逾期借款***************");
    }

    public void overdue(Borrow borrow) {
        // 逾期天数
        Integer yqDays = borrow.getYqDays() + 1;
        // 逾期利息
        Long yqlxAmount = borrow.getYqlxAmount();
        if (yqDays <= 7) {
            yqlxAmount += AmountUtil.eraseLiUp(AmountUtil.mul(
                borrow.getAmount(), borrow.getRate1()));
        } else {
            yqlxAmount += AmountUtil.eraseLiUp(AmountUtil.mul(
                borrow.getAmount(), borrow.getRate2()));
        }
        borrow.setYqDays(yqDays);
        borrow.setYqlxAmount(yqlxAmount);
        borrow.setTotalAmount(borrow.getAmount() + yqlxAmount);
        borrow.setStatus(EBorrowStatus.OVERDUE.getCode());
        borrow.setUpdater("程序自动");
        borrow.setUpdateDatetime(new Date());
        borrow.setRemark("已逾期");
        borrowBO.overdue(borrow);

        // 更新申请单状态
        applyBO.refreshCurrentApplyStatus(borrow.getApplyUser(),
            EApplyStatus.OVERDUE);

    }

    @Override
    public void archive(String code, String updater, String remark) {
        Borrow data = borrowBO.getBorrow(code);
        data.setIsArchive(EBoolean.YES.getCode());
        data.setUpdater(updater);
        data.setUpdateDatetime(new Date());
        data.setRemark(remark);
        borrowBO.archive(data);
    }

}
