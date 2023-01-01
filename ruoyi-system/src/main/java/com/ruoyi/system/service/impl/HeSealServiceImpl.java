package com.ruoyi.system.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.itextpdf.text.BadElementException;
import com.itextpdf.text.Image;
import com.ruoyi.common.core.mybatisplus.core.ServicePlusImpl;
import com.ruoyi.common.core.page.PagePlus;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.utils.PageUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.fabric.FabricUtils;
import com.ruoyi.common.utils.ipfs.IPFSUtils;
import com.ruoyi.system.domain.HeSeal;
import com.ruoyi.system.domain.bo.HeSealBo;
import com.ruoyi.system.domain.vo.HeContractVo;
import com.ruoyi.system.domain.vo.HeSealVo;
import com.ruoyi.system.mapper.HeSealMapper;
import com.ruoyi.system.service.IHeSealService;
import com.ruoyi.system.service.ISysOssService;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.fabric.gateway.Contract;
import org.hyperledger.fabric.gateway.ContractException;
import org.hyperledger.fabric.gateway.Network;
import org.hyperledger.fabric.sdk.Peer;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 印章管理Service业务层处理
 *
 * @author henriport
 * @date 2021-10-18
 */
@Slf4j
@Service
public class HeSealServiceImpl extends ServicePlusImpl<HeSealMapper, HeSeal, HeSealVo> implements IHeSealService {
    @Resource
    private FabricUtils fabricUtils;
    @Resource
    private ISysOssService iSysOssService;

    @Override
    public HeSealVo queryById(Long id) {
        return getVoById(id);
    }

    @Override
    public TableDataInfo<HeSealVo> queryPageList(HeSealBo bo) {
        PagePlus<HeSeal, HeSealVo> result = pageVo(PageUtils.buildPagePlus(), buildQueryWrapper(bo));
        return PageUtils.buildDataInfo(result);
    }

    @Override
    public List<HeSealVo> queryList(HeSealBo bo) {
        return listVo(buildQueryWrapper(bo));
    }

    @Override
    public List<HeSeal> queryListByUserId(Long userId) {
        QueryWrapper<HeSeal> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("state", 2).eq("belong", userId).select("id", "title", "oss_url");
        return list(queryWrapper);
    }

    private LambdaQueryWrapper<HeSeal> buildQueryWrapper(HeSealBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<HeSeal> lqw = Wrappers.lambdaQuery();
        lqw.eq(bo.getId() != null, HeSeal::getId, bo.getId());
        lqw.eq(StringUtils.isNotBlank(bo.getTitle()), HeSeal::getTitle, bo.getTitle());
        lqw.eq(StringUtils.isNotBlank(bo.getOssUrl()), HeSeal::getOssUrl, bo.getOssUrl());
        lqw.eq(StringUtils.isNotBlank(bo.getIpfsHash()), HeSeal::getIpfsHash, bo.getIpfsHash());
        lqw.eq(bo.getBelong() != null, HeSeal::getBelong, bo.getBelong());
        lqw.eq(bo.getIsLink() != null, HeSeal::getIsLink, bo.getIsLink());
        lqw.eq(bo.getIsDelete() != null, HeSeal::getIsDelete, bo.getIsDelete());
        lqw.eq(StringUtils.isNotBlank(bo.getState()), HeSeal::getState, bo.getState());
        lqw.orderByAsc(HeSeal::getId);
        return lqw;
    }

    @Override
    public Boolean insertByBo(HeSealBo bo) {
        HeSeal add = BeanUtil.toBean(bo, HeSeal.class);
        validEntityBeforeSave(add);
        boolean flag = save(add);
        if (flag) {
            bo.setId(add.getId());
            //这里是同样的问题，依旧是save()方法导致的数据错误，需要调用一下更新方法
            updateByBo(bo);
        }
        return flag;
    }

    @Override
    public Boolean updateByBo(HeSealBo bo) {
        // 修改印章的逻辑，印章没有审批流程，所以只需要在修改操作中进行印章状态的修改
        // 后台依据从前端传来的状态来进行印章信息的出入链操作
        // 获取数据库中的数据
        HeSealVo heSealVo = queryById(bo.getId());
        // 印章入链就是印章状态是已通过 且 是否入链为否 且 数据库中的印章状态不为已通过
        if (heSealVo.getIsLink() != 1 &&
            "2".equals(bo.getState()) &&
            !"2".equals(heSealVo.getState())) {
            Image seal = null;
            byte[] data = new byte[0];
            try {
                // 读取seal
                seal = Image.getInstance(heSealVo.getOssUrl());
                // 将Image文件转成byte[]
                data = seal.getRawData();
                // TODO 调用文件入ipfs方法
                String ipfsHash = IPFSUtils.upload(data);
                // TODO 调用数据入链方法，整体数据存入Fabric
                // 初始化区块链
                Network network = fabricUtils.getNetwork();
                Contract contract = fabricUtils.getContract();
                byte[] addSealResult = contract.createTransaction("addSeal")
                    .setEndorsingPeers(network.getChannel().getPeers(EnumSet.of(Peer.PeerRole.ENDORSING_PEER)))
                    .submit(
                        bo.getId().toString(),
                        bo.getTitle(),
                        ipfsHash,
                        bo.getBelong().toString());
                log.info("addSeal：" + new String(addSealResult, StandardCharsets.UTF_8));
                // 设置id对应的印章数据
                bo.setIpfsHash(ipfsHash);
                bo.setState("2");
                bo.setIsDelete(0);
                bo.setIsLink(1);
            } catch (BadElementException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (ContractException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
            HeSeal update = BeanUtil.toBean(bo, HeSeal.class);
            validEntityBeforeSave(update);
            return updateById(update);
        }
        boolean isSame =
            !bo.getTitle().equals(heSealVo.getTitle()) ||
                !bo.getOssUrl().equals(heSealVo.getOssUrl()) ||
                !bo.getIpfsHash().equals(heSealVo.getIpfsHash()) ||
                !bo.getBelong().equals(heSealVo.getBelong()) ||
                !bo.getState().equals(heSealVo.getState());
        // 印章出链就是数据发生改变 且 该印章本身是入链 且 印章状态状态不能是已通过 且 数据库印章状态是已通过
        if (isSame && heSealVo.getIsLink() == 1) {
            // TODO 调用数据出链方法，删除对应的数据
            // 初始化
            Network network = fabricUtils.getNetwork();
            Contract contract = fabricUtils.getContract();
            // 当印章的状态为入链状态才进行区块删除
            byte[] deleteSealResult = new byte[0];
            try {
                deleteSealResult = contract.createTransaction("deleteSealById")
                    .setEndorsingPeers(network.getChannel().getPeers(EnumSet.of(Peer.PeerRole.ENDORSING_PEER)))
                    .submit(
                        bo.getId().toString());
            } catch (ContractException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (TimeoutException e) {
                e.printStackTrace();
            }
            log.info("deleteSealById：" +new String(deleteSealResult, StandardCharsets.UTF_8));
            //出链完毕，还需要更新一下是否删除的状态
            bo.setIsDelete(1);
            //出链完毕，还需要更新一下是否入链的状态
            bo.setIsLink(0);
            if ("3".equals(bo.getState())) {
                bo.setState("3");
            } else {
                bo.setState("1");
            }
        }
        HeSeal update = BeanUtil.toBean(bo, HeSeal.class);
        validEntityBeforeSave(update);
        return updateById(update);
    }

    /**
     * 保存前的数据校验
     *
     * @param entity 实体类数据
     */
    private void validEntityBeforeSave(HeSeal entity) {
        //TODO 做一些数据校验,如唯一约束
    }

    @Override
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if (isValid) {
            //TODO 做一些业务上的校验,判断是否需要校验
            // 获取ossurl
            Collection<String> urlList;
            List<HeSeal> heSeals = listByIds(ids);
            urlList = heSeals.stream().map(heSeal -> heSeal.getOssUrl()).collect(Collectors.toList());
            // 删除oss文件
            iSysOssService.deleteWithValidByUrls(urlList);
            // TODO 调用数据出链方法，删除对应的数据
            // 初始化区块链
            Network network = fabricUtils.getNetwork();
            Contract contract = fabricUtils.getContract();
            ids.forEach(id -> {
                // 获取印章
                HeSealVo heSealVo = queryById(id);
                // 当合同的状态是入链状态才进行区块删除
                if (heSealVo.getIsLink() == 1) {
                    byte[] deleteSealResult = new byte[0];
                    try {
                        deleteSealResult = contract.createTransaction("deleteSealById")
                            .setEndorsingPeers(network.getChannel().getPeers(EnumSet.of(Peer.PeerRole.ENDORSING_PEER)))
                            .submit(
                                id.toString());
                    } catch (ContractException e) {
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (TimeoutException e) {
                        e.printStackTrace();
                    }
                    log.info("deleteSealById：" + new String(deleteSealResult, StandardCharsets.UTF_8));
                }
            });
        }
        return removeByIds(ids);
    }
}
