package com.ymatou.productprice.domain.service;

import com.google.common.primitives.Doubles;
import com.ymatou.productprice.domain.repo.RepositoryProxy;
import com.ymatou.productprice.domain.repo.Repository;
import com.ymatou.productprice.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 商品价格服务相关
 * Created by chenpengxuan on 2017/3/2.
 */
@Component
public class PriceQueryService {
    @Autowired
    RepositoryProxy repositoryProxy;

    @Autowired
    private PriceCoreService priceCoreService;

    private Repository repository;

    @PostConstruct
    public void  init(){
        repository = repositoryProxy.getRepository();
    }

    /**
     * 根据商品id获取价格信息
     *
     * @param buyerId
     * @param productId
     * @param isTradeIsolation
     * @return
     */
    public ProductPrice getPriceInfoByProductId(int buyerId,
                                                String productId,
                                                boolean isTradeIsolation) throws BizException {
        //组装商品价格信息
        ProductPrice productPrice = new ProductPrice();
        productPrice.setProductId(productId);

        //查询商品规格信息列表
        List<Catalog> catalogList = repository.getCatalogListByProduct(productId);
        if (catalogList == null || catalogList.isEmpty()) {
            BizException.throwBizException("商品信息不存在");
        }

        //查询活动商品信息
        Map<String, Object> activityProductInfo = repository.getActivityProduct(productId);

        //价格核心逻辑
        priceCoreService.calculateRealPriceCoreLogic(buyerId,
                catalogList,
                Arrays.asList(productPrice),
                Arrays.asList(activityProductInfo),
                isTradeIsolation);

        return productPrice;
    }

    /**
     * 根据商品id获取价格信息
     *
     * @param buyerId
     * @param productIdList
     * @param isTradeIsolation
     * @return
     */
    public List<ProductPrice> getPriceInfoByProductIdList(int buyerId,
                                                          List<String> productIdList,
                                                          boolean isTradeIsolation) throws BizException {
        //组装商品价格信息列表
        List<ProductPrice> productPriceList = productIdList.stream().map(x -> {
            ProductPrice tempProductPrice = new ProductPrice();
            tempProductPrice.setProductId(x);
            return tempProductPrice;
        }).collect(Collectors.toList());

        //查询所有商品的规格信息
        List<Catalog> catalogList = repository.getCatalogListByProduct
                (productIdList.stream().distinct().collect(Collectors.toList()));

        if (catalogList == null || catalogList.isEmpty()) {
            BizException.throwBizException("商品信息不存在");
        }
        //查询活动商品列表
        List<Map<String, Object>> activityProductList = repository.getActivityProductList(productIdList);

        //价格核心逻辑
        priceCoreService.calculateRealPriceCoreLogic(buyerId,
                catalogList,
                productPriceList,
                activityProductList,
                isTradeIsolation);
        return productPriceList;
    }

    /**
     * 根据商品id获取价格信息（用于新增接口->搜索商品列表）
     *
     * @param buyerId
     * @param productIdList
     * @param isTradeIsolation
     * @return
     */
    public List<ProductPriceForSearched> getPriceInfoByProductIdListForSearched(int buyerId,
                                                                     List<String> productIdList,
                                                                     boolean isTradeIsolation) throws BizException {
        //组装商品价格信息列表
        List<ProductPriceForSearched> productPriceList = productIdList.stream().distinct().map(x -> {
            ProductPriceForSearched tempProductPrice = new ProductPriceForSearched();
            tempProductPrice.setProductId(x);
            return tempProductPrice;
        }).collect(Collectors.toList());

        //查询所有商品的价格区间信息并进行组装
        List<Map<String,Object>> productList = repository.getPriceRangeListByProduct
                (productIdList.stream().distinct().collect(Collectors.toList()));

        if (productList == null || productList.isEmpty()) {
            BizException.throwBizException("商品信息不存在");
        }

        productPriceList.stream().forEach(x -> {
            Map<String,Object> tempProductMap = productList
                    .stream()
                    .filter(xx -> xx.get("spid").equals(x.getProductId())).findAny().orElse(Collections.emptyMap());
            String[] maxPriceList = ((String)tempProductMap.get("maxp")).split(",");
            String[] minPriceList = ((String)tempProductMap.get("minp")).split(",");
            //设置原价区间
            x.setMinOriginalPrice(Doubles.tryParse(minPriceList[0]));
            x.setMaxOriginalPrice(Doubles.tryParse(maxPriceList[0]));
            //设置新客价区间
            x.setMinNewpersonPrice(Doubles.tryParse(minPriceList[1]));
            x.setMaxNewpersonPrice(Doubles.tryParse(maxPriceList[1]));
            //设置vip价区间
            x.setMinVipPrice(Doubles.tryParse(minPriceList[2]));
            x.setMaxVipPrice(Doubles.tryParse(maxPriceList[2]));
            //设置sellerId
            x.setSellerId(new Long(Optional.ofNullable((int)tempProductMap.get("sid")).orElse(0)));
        });

        //查询活动商品列表
        List<Map<String, Object>> activityProductList = repository.getActivityProductList(productIdList);

        //价格核心逻辑
        priceCoreService.calculateRealPriceCoreLogic(buyerId, productPriceList,activityProductList, isTradeIsolation);
        return productPriceList;
    }

    /**
     * 根据规格id列表获取价格信息
     *
     * @param buyerId
     * @param catalogIdList
     * @param isTradeIsolation
     * @return
     */
    public List<CatalogPrice> getPriceInfoByCatalogIdList(int buyerId, List<String> catalogIdList, boolean isTradeIsolation) throws BizException {
        //过滤重复catalogId
        catalogIdList = catalogIdList.stream().distinct().collect(Collectors.toList());

        //获取规格信息
        List<Catalog> catalogList = repository.getCatalogByCatalogId(catalogIdList);
        if (catalogList == null || catalogList.isEmpty()) {
            BizException.throwBizException("商品信息不存在");
        }
        //组装商品id列表
        List<String> productIdList = catalogList.stream().map(catalog -> catalog.getProductId()).distinct().collect(Collectors.toList());

        //组装商品价格信息列表
        List<ProductPrice> productPriceList = productIdList.stream().map(x -> {
            ProductPrice tempProductPrice = new ProductPrice();
            tempProductPrice.setProductId(x);
            return tempProductPrice;
        }).collect(Collectors.toList());

        //查询活动商品列表
        List<Map<String, Object>> activityProductList = repository.getActivityProductList(productIdList);

        //价格核心逻辑
        priceCoreService.calculateRealPriceCoreLogic(buyerId, catalogList, productPriceList, activityProductList, isTradeIsolation);

        //组装规格价格信息列表
        List<CatalogPrice> catalogPriceList = new ArrayList<>();
        productPriceList.stream().forEach(productPrice -> {
            List<CatalogPrice> tempCatalogPriceList = productPrice.getCatalogs().stream().map(catalog -> {

                CatalogPrice catalogPrice = new CatalogPrice();
                catalogPrice.setProductId(productPrice.getProductId());
                catalogPrice.setCatalogInfo(catalog);
                catalogPrice.setHasConfirmedOrders(productPrice.getHasConfirmedOrders());
                catalogPrice.setNoOrdersOrAllCancelled(productPrice.getNoOrdersOrAllCancelled());
                return catalogPrice;
            }).collect(Collectors.toList());

            catalogPriceList.addAll(tempCatalogPriceList);
        });
        return catalogPriceList;
    }
}
