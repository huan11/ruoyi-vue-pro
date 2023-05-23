package cn.iocoder.yudao.module.product.service.spu;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.product.controller.admin.sku.vo.ProductSkuCreateOrUpdateReqVO;
import cn.iocoder.yudao.module.product.controller.admin.sku.vo.ProductSkuRespVO;
import cn.iocoder.yudao.module.product.controller.admin.spu.vo.*;
import cn.iocoder.yudao.module.product.controller.app.spu.vo.AppProductSpuPageReqVO;
import cn.iocoder.yudao.module.product.convert.sku.ProductSkuConvert;
import cn.iocoder.yudao.module.product.convert.spu.ProductSpuConvert;
import cn.iocoder.yudao.module.product.dal.dataobject.category.ProductCategoryDO;
import cn.iocoder.yudao.module.product.dal.dataobject.sku.ProductSkuDO;
import cn.iocoder.yudao.module.product.dal.dataobject.spu.ProductSpuDO;
import cn.iocoder.yudao.module.product.dal.mysql.spu.ProductSpuMapper;
import cn.iocoder.yudao.module.product.enums.ProductConstants;
import cn.iocoder.yudao.module.product.enums.spu.ProductSpuStatusEnum;
import cn.iocoder.yudao.module.product.enums.spu.ProductSpuPageTabEnum;
import cn.iocoder.yudao.module.product.service.brand.ProductBrandService;
import cn.iocoder.yudao.module.product.service.category.ProductCategoryService;
import cn.iocoder.yudao.module.product.service.property.ProductPropertyValueService;
import cn.iocoder.yudao.module.product.service.property.bo.ProductPropertyValueDetailRespBO;
import cn.iocoder.yudao.module.product.service.sku.ProductSkuService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.getSumValue;
import static cn.iocoder.yudao.module.product.enums.ErrorCodeConstants.*;

/**
 * 商品 SPU Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
public class ProductSpuServiceImpl implements ProductSpuService {

    @Resource
    private ProductSpuMapper productSpuMapper;

    @Resource
    @Lazy // 循环依赖，避免报错
    private ProductSkuService productSkuService;
    @Resource
    private ProductBrandService brandService;
    @Resource
    private ProductCategoryService categoryService;
    @Resource
    private ProductPropertyValueService productPropertyValueService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createSpu(ProductSpuCreateReqVO createReqVO) {
        // 校验分类 TODO puhui999：暂不清楚为什么只能选择第三层的结点；芋艿：改成二级分类，因为商品只能放在叶子节点级别；fix
        validateCategory(createReqVO.getCategoryId());
        brandService.validateProductBrand(createReqVO.getBrandId());
        List<ProductSkuCreateOrUpdateReqVO> skuSaveReqList = createReqVO.getSkus();
        // 校验 SKU
        productSkuService.validateSkuList(skuSaveReqList, createReqVO.getSpecType());
        ProductSpuDO spu = ProductSpuConvert.INSTANCE.convert(createReqVO);
        // 初始化 SPU 中 SKU 相关属性
        initSpuFromSkus(spu, skuSaveReqList);

        // 插入 SPU
        productSpuMapper.insert(spu);
        // 插入 SKU
        productSkuService.createSkuList(spu.getId(), skuSaveReqList);
        // 返回
        return spu.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSpu(ProductSpuUpdateReqVO updateReqVO) {
        // 校验 SPU 是否存在
        validateSpuExists(updateReqVO.getId());
        // 校验分类
        validateCategory(updateReqVO.getCategoryId());
        // 校验品牌
        brandService.validateProductBrand(updateReqVO.getBrandId());
        // 校验SKU
        List<ProductSkuCreateOrUpdateReqVO> skuSaveReqList = updateReqVO.getSkus();
        productSkuService.validateSkuList(skuSaveReqList, updateReqVO.getSpecType());
        // 更新 SPU
        ProductSpuDO updateObj = ProductSpuConvert.INSTANCE.convert(updateReqVO);
        initSpuFromSkus(updateObj, skuSaveReqList);
        productSpuMapper.updateById(updateObj);
        // 批量更新 SKU
        productSkuService.updateSkuList(updateObj.getId(), updateReqVO.getSkus());
    }

    /**
     * 基于 SKU 的信息，初始化 SPU 的信息
     * 主要是计数相关的字段，例如说市场价、最大最小价、库存等等
     *
     * @param spu  商品 SPU
     * @param skus 商品 SKU 数组
     */
    private void initSpuFromSkus(ProductSpuDO spu, List<ProductSkuCreateOrUpdateReqVO> skus) {
        // 断言，避免告警
        assert skus.size() > 0;
        // 获取sku单价最低的商品
        ProductSkuCreateOrUpdateReqVO vo = skus.stream().min(Comparator.comparing(ProductSkuCreateOrUpdateReqVO::getPrice)).get();
        // sku单价最低的商品的价格
        spu.setPrice(vo.getPrice());
        // sku单价最低的商品的市场价格
        spu.setMarketPrice(vo.getMarketPrice());
        // sku单价最低的商品的成本价格
        spu.setCostPrice(vo.getCostPrice());
        // sku单价最低的商品的条形码
        spu.setBarCode(vo.getBarCode());
        // skus库存总数
        spu.setStock(getSumValue(skus, ProductSkuCreateOrUpdateReqVO::getStock, Integer::sum));
        // 若是 spu 已有状态则不处理
        if (spu.getStatus() == null) {
            // 默认状态为上架
            spu.setStatus(ProductSpuStatusEnum.ENABLE.getStatus());
            // 默认商品销量
            spu.setSalesCount(ProductConstants.SALES_COUNT);
            // 默认商品浏览量
            spu.setBrowseCount(ProductConstants.BROWSE_COUNT);
        }
    }

    /**
     * 校验商品分类是否合法
     *
     * @param id 商品分类编号
     */
    private void validateCategory(Long id) {
        categoryService.validateCategory(id);
        // 校验层级
        if (categoryService.getCategoryLevel(id) != ProductConstants.CATEGORY_LEVEL) {
            throw exception(SPU_SAVE_FAIL_CATEGORY_LEVEL_ERROR);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSpu(Long id) {
        // 校验存在
        validateSpuExists(id);
        // 校验商品状态不是回收站不能删除
        validateSpuStatus(id);
        // 删除 SPU
        productSpuMapper.deleteById(id);
        // 删除关联的 SKU
        productSkuService.deleteSkuBySpuId(id);
    }

    private void validateSpuExists(Long id) {
        if (productSpuMapper.selectById(id) == null) {
            throw exception(SPU_NOT_EXISTS);
        }
    }

    /**
     * 验证 SPU 状态是否为回收站
     *
     * @param id id
     */
    private void validateSpuStatus(Long id) {
        ProductSpuDO spuDO = productSpuMapper.selectById(id);
        // 判断 SPU 状态是否为回收站
        if (ObjectUtil.notEqual(spuDO.getStatus(), ProductSpuStatusEnum.RECYCLE.getStatus())) {
            throw exception(SPU_NOT_RECYCLE);
        }
    }

    @Override
    public ProductSpuDO getSpu(Long id) {
        return productSpuMapper.selectById(id);
    }

    @Override
    public List<ProductSpuDO> getSpuList(Collection<Long> ids) {
        return productSpuMapper.selectBatchIds(ids);
    }

    @Override
    public List<ProductSpuDO> getSpuList() {
        return productSpuMapper.selectList();
    }

    @Override
    public List<ProductSpuDO> getSpuList(ProductSpuExportReqVO reqVO) {
        return productSpuMapper.selectList(reqVO);
    }

    @Override
    public PageResult<ProductSpuDO> getSpuPage(ProductSpuPageReqVO pageReqVO) {
        return productSpuMapper.selectPage(pageReqVO);
    }

    @Override
    public PageResult<ProductSpuDO> getSpuPage(AppProductSpuPageReqVO pageReqVO) {
        //return productSpuMapper.selectPage(pageReqVO); TODO 有差异接口接受参数类型不对
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSpuStock(Map<Long, Integer> stockIncrCounts) {
        stockIncrCounts.forEach((id, incCount) -> productSpuMapper.updateStock(id, incCount));
    }

    @Override
    public ProductSpuDetailRespVO getSpuDetail(Long id) {
        // 获得商品 SPU
        ProductSpuDO spu = getSpu(id);
        if (spu == null) {
            throw exception(SPU_NOT_EXISTS);
        }
        // 查询商品 SKU
        List<ProductSkuDO> skus = productSkuService.getSkuListBySpuId(spu.getId());
        return ProductSpuConvert.INSTANCE.convertForSpuDetailRespVO(spu, skus, productPropertyValueService::getPropertyValueDetailList);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(ProductSpuUpdateStatusReqVO updateReqVO) {
        // 校验存在
        validateSpuExists(updateReqVO.getId());
        // 更新状态
        ProductSpuDO productSpuDO = productSpuMapper.selectById(updateReqVO.getId()).setStatus(updateReqVO.getStatus());
        productSpuMapper.updateById(productSpuDO);

    }

    @Override
    public Map<Integer, Long> getTabsCount() {
        // TODO @puhui999：map =》；尽量避免出现 map 这种命名，无命名含义哈 fix
        Map<Integer, Long> counts = new HashMap<>(ProductConstants.SPU_TAB_COUNTS);
        // 查询销售中的商品数量
        counts.put(ProductSpuPageTabEnum.FOR_SALE.getType(), productSpuMapper.selectCount(ProductSpuDO::getStatus, ProductSpuStatusEnum.ENABLE.getStatus()));
        // 查询仓库中的商品数量
        counts.put(ProductSpuPageTabEnum.IN_WAREHOUSE.getType(), productSpuMapper.selectCount(ProductSpuDO::getStatus, ProductSpuStatusEnum.DISABLE.getStatus()));
        // 查询售空的商品数量
        counts.put(ProductSpuPageTabEnum.SOLD_OUT.getType(), productSpuMapper.selectCount(ProductSpuDO::getStock, 0));
        // 查询触发警戒库存的商品数量
        // TODO @puhui999：要有空格；, productSpuMapper fix
        // TODO @puhui999：Service 不要有 Mapper 的逻辑；想想咋抽象一下哈 fix：调整为在 productSpuMapper 中书写逻辑
        counts.put(ProductSpuPageTabEnum.ALERT_STOCK.getType(), productSpuMapper.selectCountByStockAndStatus());
        // 查询回收站中的商品数量
        counts.put(ProductSpuPageTabEnum.RECYCLE_BIN.getType(), productSpuMapper.selectCount(ProductSpuDO::getStatus, ProductSpuStatusEnum.RECYCLE.getStatus()));
        return counts;
    }

}
