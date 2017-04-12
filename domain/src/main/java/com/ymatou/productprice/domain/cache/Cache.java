package com.ymatou.productprice.domain.cache;

import com.alibaba.fastjson.JSON;
import com.google.common.cache.CacheStats;
import com.google.common.collect.Maps;
import com.google.common.primitives.Longs;
import com.ymatou.productprice.domain.model.ActivityProduct;
import com.ymatou.productprice.domain.model.Catalog;
import com.ymatou.productprice.domain.model.ProductPriceData;
import com.ymatou.productprice.domain.repo.Repository;
import com.ymatou.productprice.infrastructure.config.props.BizProps;
import com.ymatou.productprice.infrastructure.util.CacheUtil.CacheManager;
import com.ymatou.productprice.infrastructure.util.LogWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * 缓存 数据操作相关
 * Created by chenpengxuan on 2017/3/22.
 */
@Component
public class Cache {
    /**
     * 缓存工具类
     */
    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private BizProps bizProps;

    @Resource(name = "mongoRepository")
    private Repository mongoRepository;

    @Resource(name = "parallelRepository")
    private Repository parallelRepository;

    @Autowired
    private LogWrapper logWrapper;

    private Repository realBusinessRepository;

    @PostConstruct
    public void init() {
        if (bizProps.isUseParallel()) {
            realBusinessRepository = parallelRepository;
        } else {
            realBusinessRepository = mongoRepository;
        }
    }

    /**
     * 获取缓存统计信息
     *
     * @return
     */
    public CacheStats getCacheStats() {
        return cacheManager.getCacheStats();
    }

    /**
     * 获取规格信息列表
     *
     * @param productId
     * @return
     */
    public List<Catalog> getCatalogListByProduct(String productId, Date catalogUpdateTime) throws IllegalArgumentException {
        ProductPriceData tempData = cacheManager.get(
                productId,

                obj -> obj,

                obj -> realBusinessRepository.getCatalogListByProduct(productId),

                (pid, productPriceData) -> {
                    Long catalogUpdateStamp = catalogUpdateTime.getTime();
                    return productPriceData != null
                            && productPriceData.getCatalogList() != null
                            && !productPriceData.getCatalogList().isEmpty()
                            && productPriceData.getCatalogList()
                            .stream()
                            .allMatch(x ->
                                    Longs.compare(x.getUpdateTime() != null
                                            ? x.getUpdateTime().getTime() : 0L, catalogUpdateStamp) == 0);
                },

                ((productPriceData, catalogList) -> {
                    if (productPriceData == null) {
                        if (catalogList != null && !catalogList.isEmpty()) {
                            productPriceData = new ProductPriceData();
                            productPriceData.setSellerId(catalogList.stream().findAny().get().getSellerId());
                            productPriceData.setCatalogList(catalogList);
                        }
                        else{
                            throw new IllegalArgumentException("catalog不能为空,ProductId为:" + productId);
                        }
                    } else {
                        productPriceData.setCatalogList(catalogList);
                    }
                    return productPriceData;
                })
        );
        return tempData != null ? tempData.getCatalogList() : null;
    }

    /**
     * 创建新缓存数据
     *
     * @param catalogList
     */
    private void createNewCacheData(List<Catalog> catalogList) {
        //将从数据库取到的最新数据刷新缓存结构
        Map<String, List<Catalog>> cacheGroup = catalogList
                .stream()
                .collect(Collectors.groupingBy(Catalog::getProductId));
        Map<String, ProductPriceData> cacheMap = Maps.transformValues(cacheGroup, v -> {
            ProductPriceData tempData = new ProductPriceData();
            tempData.setCatalogList(v);
            return tempData;
        });

        //批量刷缓存
        cacheManager.put(cacheMap);
    }

    /**
     * 过滤有效数据
     *
     * @param catalogList
     * @param catalogUpdateTimeMap
     */
    private List<Catalog> filterValidCacheData(List<Catalog> catalogList,
                                               Map<String, Date> catalogUpdateTimeMap) {
        //过滤有效的业务数据
        return catalogList
                .stream()
                .filter(c -> {
                    //缓存中规格更新时间戳
                    Long cacheCatalogUpdateStamp = c.getUpdateTime() != null
                            ? c.getUpdateTime().getTime() : 0L;

                    //时间戳表中规格更新时间戳
                    Long catalogUpdateStamp = catalogUpdateTimeMap.get(c.getProductId()).getTime();

                    //时间戳比较，不相等则表示发生变更，业务数据需要重新到数据库拉取
                    return Long.compare(cacheCatalogUpdateStamp, catalogUpdateStamp) == 0;
                })
                .collect(Collectors.toList());
    }

    /**
     * 更新缓存数据
     *
     * @param reloadCatalogGroup
     */
    private void updateCacheData(List<ProductPriceData> cacheProductList,
                                 Map<String, List<Catalog>> reloadCatalogGroup) {
        //创建批量刷缓存的结构
        Map<String, ProductPriceData> batchRefreshCacheMap = new HashMap<>();

        reloadCatalogGroup.entrySet()
                .forEach(x -> {
                    ProductPriceData tempData = cacheProductList
                            .stream()
                            .filter(xx -> xx.getProductId().equals(x.getKey()))
                            .findAny()
                            .orElse(null);

                    //针对没有命中的数据，构建缓存结构
                    if (tempData == null) {
                        tempData = new ProductPriceData();
                    }
                    //不管业务数据过期还是缓存没有命中，都需要重新设置数据
                    tempData.setCatalogList(x.getValue());

                    //填充批量缓存的数据
                    batchRefreshCacheMap.put(x.getKey(), tempData);
                });

        //根据批量缓存结构进行批量缓存
        cacheManager.put(batchRefreshCacheMap);
    }

    /**
     * 处理并组装商品规格缓存数据
     *
     * @param productIdList
     * @param cacheProductList
     * @param catalogUpdateTimeMap
     * @return
     */
    private List<Catalog> processProductPriceDataCacheList(List<String> productIdList,
                                                           List<ProductPriceData> cacheProductList,
                                                           Map<String, Date> catalogUpdateTimeMap) {
        List<Catalog> result;

        logWrapper.recordInfoLog("processProductPriceDataCacheList_cacheProductList:JsonInfo{}",
                JSON.toJSONString(cacheProductList));

        //缓存全部没有命中的情况
        if (cacheProductList == null || cacheProductList.isEmpty()) {
            //从数据库中获取数据
            List<Catalog> catalogList = realBusinessRepository.getCatalogListByProduct(productIdList);

            result = catalogList;

            //设置缓存
            createNewCacheData(catalogList);

            return result;
        } else {
            //从缓存中获取需要的数据
            List<Catalog> cacheCatalogList = new ArrayList<>();
            List<String> catalogIdList = new ArrayList<>();

            cacheProductList.forEach(x ->
                    x.getCatalogList()
                            .forEach(xx -> {
                                cacheCatalogList.add(xx);
                                catalogIdList.add(xx.getCatalogId());
                            }));

            //针对其他接口缓存场景的只缓存部分catalog的情况，需要从mongo中取完整的catalogIdList
//            List<String> catalogIdList = realBusinessRepository.getCatalogIdByProductIdList(productIdList)
//                    .stream()
//                    .map(x -> Optional.ofNullable((String)x.get("cid")).orElse(""))
//                    .filter(xx -> !xx.isEmpty())
//                    .distinct()
//                    .collect(Collectors.toList());

            //过滤有效业务缓存数据
            List<Catalog> validCatalogList = filterValidCacheData(cacheCatalogList, catalogUpdateTimeMap);

            //获取有效缓存数据
            List<String> validCatalogIdList = validCatalogList
                    .stream()
                    .map(Catalog::getCatalogId)
                    .distinct()
                    .collect(Collectors.toList());

            //组装有效数据
            result = new ArrayList<>();
            result.addAll(validCatalogList);

            //传入的productId与有效数据对应的productId列表的差集就是需要重新拉取的数据
            List<String> needReloadCatalogIdList = new ArrayList<>();

            needReloadCatalogIdList.addAll(catalogIdList);
            needReloadCatalogIdList.removeAll(validCatalogIdList);

            if (!needReloadCatalogIdList.isEmpty()) {
                //需要重新刷缓存的数据
                List<Catalog> reloadCatalogList = realBusinessRepository.getCatalogByCatalogId(needReloadCatalogIdList);
                reloadCatalogList.removeAll(Collections.singleton(null));
                if (reloadCatalogList != null && !reloadCatalogList.isEmpty()) {
                    Map<String, List<Catalog>> reloadCatalogGroup = reloadCatalogList
                            .stream()
                            .collect(Collectors.groupingBy(Catalog::getProductId));

                    //更新缓存
                    updateCacheData(cacheProductList, reloadCatalogGroup);

                    //添加重刷的有效数据
                    result.addAll(reloadCatalogList);
                }
            }

            return result;
        }
    }

    /**
     * 获取规格信息列表
     *
     * @param productIdList
     * @return
     */
    public List<Catalog> getCatalogListByProduct(List<String> productIdList,
                                                 Map<String, Date> catalogUpdateTimeMap) {

        //过滤重复商品id
        productIdList = productIdList
                .stream()
                .distinct()
                .collect(Collectors.toList());

        //根据商品id列表获取缓存信息
        List<ProductPriceData> cacheProductList = cacheManager.get(productIdList).values()
                .stream()
                .map(x -> (ProductPriceData) x)
                .collect(Collectors.toList());

        return processProductPriceDataCacheList(productIdList, cacheProductList, catalogUpdateTimeMap);
    }

    /**
     * 根据规格id获取规格信息列表
     *
     * @param mapList
     * @return
     */
    public List<Catalog> getCatalogByCatalogId(List<Map<String, Object>> mapList,
                                               Map<String, Date> catalogUpdateTimeMap) {
        //根据商品id列表获取缓存信息
        List<String> productIdList = mapList
                .stream()
                .map(x -> x.get("spid").toString())
                .distinct()
                .collect(Collectors.toList());

        List<ProductPriceData> cacheProductList = cacheManager.get(productIdList).values()
                .stream()
                .map(x -> (ProductPriceData) x)
                .collect(Collectors.toList());

        return processProductPriceDataCacheList(productIdList, cacheProductList, catalogUpdateTimeMap);
    }

    /**
     * 初始化活动商品缓存
     */
    public int initActivityProductCache() {
        List<ActivityProduct> activityProductList = realBusinessRepository.getAllValidActivityProductList();
        cacheManager.putActivityProduct(activityProductList
                .stream()
                .collect(Collectors.toMap(ActivityProduct::getProductId, y -> y, (key1, key2) -> key2))
        );
        return activityProductList.size();
    }

    /**
     * 添加活动商品增量信息
     */
    public void addNewestActivityProductCache() {
        ConcurrentMap activityProductCache = cacheManager.getActivityProductCacheContainer();

        //从缓存中获取最后创建的活动商品数据的更新日期
        //之前从Objectid取时间戳 发现只精确到秒 造成测试环境验证不正确
        ActivityProduct latestActivityProduct = (ActivityProduct) activityProductCache.values()
                .stream()
                .max((x, y) ->
                        Long.compare(((ActivityProduct) x).getUpdateTime().getTime()
                                , ((ActivityProduct) y).getUpdateTime().getTime()))
                .orElse(null);

        Date newestCacheActivityUpdateTime = latestActivityProduct != null ?
                latestActivityProduct.getUpdateTime() : null;

        if (newestCacheActivityUpdateTime != null) {
            //获取新增的mongo活动商品信息
            List<String> newestActivityProductIdList = realBusinessRepository
                    .getNewestActivityProductIdList(newestCacheActivityUpdateTime);

            List<ActivityProduct> newestActivityProductList = realBusinessRepository
                    .getActivityProductList(newestActivityProductIdList);

            //批量添加至缓存
            cacheManager.putActivityProduct(newestActivityProductList
                    .stream()
                    .collect(Collectors.toMap(ActivityProduct::getProductId, y -> y, (key1, key2) -> key2)));

            logWrapper.recordInfoLog("增量添加活动商品缓存已执行,新增{}条", newestActivityProductList.size());
        }
    }

    /**
     * 获取活动商品信息
     *
     * @param productId
     * @param activityProductUpdateTime
     * @return
     */
    public ActivityProduct getActivityProduct(String productId, Date activityProductUpdateTime) {
        ActivityProduct result;

        String cacheKey = productId;
        //先从缓存中取
        result = cacheManager.getActivityProduct(cacheKey);

        //如果缓存中没有命中，则认为此商品不是活动商品
        if (result == null) {
            return result;
        } else {
            result = processCacheActivityProduct(result, activityProductUpdateTime);
            return result;
        }
    }

    /**
     * 缓存活动商品数据处理逻辑
     *
     * @param activityProduct
     * @return
     */
    private ActivityProduct processCacheActivityProduct(ActivityProduct activityProduct, Date activityProductUpdateTime) {
        Long startTime = activityProduct.getStartTime().getTime();
        Long endTime = activityProduct.getEndTime().getTime();
        Long now = new Date().getTime();
        Long updateStamp = activityProductUpdateTime != null ? activityProductUpdateTime.getTime() : 0L;

        if (Long.compare(activityProduct.getUpdateTime().getTime(), updateStamp) != 0) {
            activityProduct = realBusinessRepository.getActivityProduct(activityProduct.getProductId());
            if(activityProduct != null){
                cacheManager.putActivityProduct(activityProduct.getProductId(), activityProduct);
                startTime = activityProduct.getStartTime().getTime();
                endTime = activityProduct.getEndTime().getTime();
            }
        }

        //过期的活动商品
        if (now > endTime) {
            cacheManager.deleteActivityProduct(activityProduct.getProductId());
            return null;
        }
        //活动商品数据发生变化，取数据重新刷缓存
        else if (now < startTime) {
            return null;
        }
        return activityProduct;
    }

    /**
     * 获取活动商品信息列表
     *
     * @param productIdList
     * @return
     */
    public List<ActivityProduct> getActivityProductList(List<String> productIdList,
                                                        Map<String, Date> activityProductStampMap) {
        productIdList = productIdList
                .stream()
                .distinct()
                .collect(Collectors.toList());

        //从缓存中获取数据
        List<ActivityProduct> cacheList = cacheManager.getActivityProduct(productIdList);
        //针对Lists.newArrayList创建的列表 排除空元素
        cacheList.removeAll(Collections.singleton(null));
        //如果缓存为空 则认为都不是活动商品
        if (cacheList == null || cacheList.isEmpty()) {
            return cacheList;
        } else {
            return cacheList
                    .stream()
                    .map(c ->
                            processCacheActivityProduct(c, activityProductStampMap.get(c.getProductId()))
                    )
                    .collect(Collectors.toList());
        }
    }

    /**
     * 根据商品id列表获取价格边界信息（用于新增接口->搜索商品列表）
     *
     * @param productIdList
     * @return
     */
    public List<ProductPriceData> getPriceRangeListByProduct(List<String> productIdList,
                                                             Map<String, Date> productUpdateStampMap) {
        productIdList = productIdList
                .stream()
                .distinct()
                .collect(Collectors.toList());

        List<ProductPriceData> result;

        //从缓存中获取数据
        List<ProductPriceData> cacheProductList = cacheManager.get(productIdList).values()
                .stream()
                .map(x -> (ProductPriceData) x)
                .collect(Collectors.toList());
        //针对Lists.newArrayList创建的列表 排除空元素
        cacheProductList.removeAll(Collections.singleton(null));
        //缓存完全不命中
        if (cacheProductList == null || cacheProductList.isEmpty()) {
            result = realBusinessRepository.getPriceRangeListByProduct(productIdList);

            cacheManager.put(result
                    .stream()
                    .collect(Collectors.toMap(ProductPriceData::getProductId, y -> y, (key1, key2) -> key2))
            );
        } else {
            //获取有效的商品缓存数据
            List<ProductPriceData> validProductPriceDataList = cacheProductList
                    .stream()
                    .filter(x -> {
                        Long cacheProductUpdateStamp = Optional.ofNullable(x.getUpdateTime())
                                .orElse(new Date()).getTime();
                        Long productUpdateStamp = productUpdateStampMap.get(x.getProductId()) != null ?
                                productUpdateStampMap.get(x.getProductId()).getTime() : 0;
                        return Long.compare(cacheProductUpdateStamp, productUpdateStamp) == 0;
                    })
                    .collect(Collectors.toList());

            List<String> validProductIdList = validProductPriceDataList
                    .stream()
                    .map(ProductPriceData::getProductId)
                    .collect(Collectors.toList());


            //过滤出业务数据过期的商品缓存列表
            List<ProductPriceData> invalidProductPriceDataList = new ArrayList<>();
            invalidProductPriceDataList.addAll(cacheProductList);
            invalidProductPriceDataList.removeAll(validProductPriceDataList);
            invalidProductPriceDataList.removeAll(Collections.singleton(null));

            //组装需要重新取数据库获取的数据
            List<String> needReloadProductIdList = new ArrayList<>();
            needReloadProductIdList.addAll(productIdList);
            needReloadProductIdList.removeAll(validProductIdList);
            List<ProductPriceData> reloadProductList = realBusinessRepository
                    .getPriceRangeListByProduct(needReloadProductIdList);

            //去除空数据
            reloadProductList.removeAll(Collections.singleton(null));
            //组装需要刷缓存的数据
            reloadProductList.forEach(x -> {
                //针对缓存结构中 商品数据过期 但是商品中规格数据可能有效的情况，保留其规格缓存数据
                ProductPriceData invalidProductCacheData = invalidProductPriceDataList
                        .stream()
                        .filter(z -> Optional.ofNullable(z.getProductId()).orElse("").equals(x.getProductId()))
                        .findAny()
                        .orElse(null);

                if (invalidProductCacheData != null) {
                    x.setCatalogList(invalidProductCacheData.getCatalogList());
                }
            });

            //批量刷缓存
            cacheManager.put(reloadProductList
                    .stream()
                    .collect(Collectors.toMap(ProductPriceData::getProductId, y -> y, (key1, key2) -> key2)));

            //合并有效数据并返回
            result = new ArrayList<>();
            result.addAll(validProductPriceDataList);
            result.addAll(reloadProductList);
        }
        return result;
    }
}
