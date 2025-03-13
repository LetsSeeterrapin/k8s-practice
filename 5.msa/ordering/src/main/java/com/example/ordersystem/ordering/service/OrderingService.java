package com.example.ordersystem.ordering.service;

import com.example.ordersystem.common.dtos.StockRabbitDto;
import com.example.ordersystem.common.service.StockInventoryService;
import com.example.ordersystem.common.service.StockRabbitmqService;
import com.example.ordersystem.ordering.controller.SseController;
import com.example.ordersystem.ordering.domain.OrderDetail;
import com.example.ordersystem.ordering.domain.Ordering;
import com.example.ordersystem.ordering.dtos.*;
import com.example.ordersystem.ordering.repository.OrderingDetailRepository;
import com.example.ordersystem.ordering.repository.OrderingRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
public class OrderingService {
    private final OrderingRepository orderingRepository;
    private final OrderingDetailRepository orderingDetailRepository;
    private final StockInventoryService stockInventoryService;
    private final StockRabbitmqService stockRabbitmqService;
    private final SseController sseController;
    private final RestTemplate restTemplate;
    private final ProductFeign productFeign;
    private final KafkaTemplate<String, Object> kafkaTemplate;


    public OrderingService(OrderingRepository orderingRepository, OrderingDetailRepository orderingDetailRepository, StockInventoryService stockInventoryService, StockRabbitmqService stockRabbitmqService, SseController sseController, RestTemplate restTemplate, ProductFeign productFeign, KafkaTemplate<String, Object> kafkaTemplate) {
        this.orderingRepository = orderingRepository;
        this.orderingDetailRepository = orderingDetailRepository;
        this.stockInventoryService = stockInventoryService;
        this.stockRabbitmqService = stockRabbitmqService;
        this.sseController = sseController;
        this.restTemplate = restTemplate;
        this.productFeign = productFeign;
        this.kafkaTemplate = kafkaTemplate;
    }

    public Ordering orderCreate(List<OrderCreateDto> dtos) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

////        방법1. cascading 없이 db저장
////        Ordering객체 생성 및 save
//        Ordering ordering = Ordering.builder().member(member).build();
//        orderingRepository.save(ordering);
////        OrderingDetail 객체 생성 및 save
//        for (OrderCreateDto o : dtos) {
//            Product product = productRepository.findById(o.getProductId()).orElseThrow(()->new EntityNotFoundException("product is not found"));
//            if (product.getStockQuantity() < o.getProductCount()) {
//                throw new IllegalArgumentException("재고부족");
//            } else {
////                재고감소 로직.
//                product.updateStockQuantity(o.getProductCount());
//            }
//            OrderDetail orderDetail = OrderDetail.builder()
//                    .ordering(ordering)
//                    .product(product)
//                    .quantity(o.getProductCount())
//                    .build();
//            orderingDetailRepository.save(orderDetail);
//        }

//        방법2. cascading 사용하여 db저장
//        Ordering객체 생성하면서 OrderingDetail객체 같이 생성
        Ordering ordering = Ordering.builder()
                .memberEmail(email)
                .build();

        for (OrderCreateDto o : dtos) {
//            product서버에 api요청을 통래 product객체를 받아와야함 -> 동기적처리 필수
            String productGetUrl = "http://dick-msa-product-service/product/" + o.getProductId();
            String token = SecurityContextHolder.getContext().getAuthentication().getCredentials().toString();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", token);
            HttpEntity<String> httpEntity = new HttpEntity<>(headers);
            ResponseEntity<ProductDto> response = restTemplate.exchange(productGetUrl, HttpMethod.GET, httpEntity, ProductDto.class);
            ProductDto productDto = response.getBody();
            System.out.println(productDto);
            int quantity = o.getProductCount();
            if (productDto.getStockQuantity() < quantity) {
                throw new IllegalArgumentException("재고부족");
            } else {
//                재고감소 api요청을 product서버에 보내야함 ->비동기처리 가능
            String productUpdateStockUrl = "http://dick-msa-product-service/product/updatestock";
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<ProductUpdateStockDto> updateEntity = new HttpEntity<>(
                    ProductUpdateStockDto.builder()
                            .productId(o.getProductId())
                            .productQuantity(o.getProductCount())
                            .build(), headers
            );
            restTemplate.exchange(productUpdateStockUrl, HttpMethod.PUT, updateEntity, Void.class);
            }

////            동시성이슈를 고려한 코드
////            redis를 통한 재고관리 및 재고잔량 확인
//            int newQuantity = stockInventoryService.decreaseStock(product.getId(), quantity);
//            if (newQuantity < 0) {
//                throw new IllegalArgumentException("재고부족");
//            }
////            rdb동기화(rabbitmq)
//            StockRabbitDto stockRabbitDto = StockRabbitDto.builder().productId(product.getId()).productCount(quantity).build();;
//            stockRabbitmqService.puslish(stockRabbitDto);

            OrderDetail orderDetail = OrderDetail.builder()
                    .ordering(ordering)
                    .productId(o.getProductId())
                    .quantity(o.getProductCount())
                    .build();
            ordering.getOrderDetails().add(orderDetail);
        }
        Ordering ordering1 = orderingRepository.save(ordering);

        return ordering;
    }

    public Ordering orderFeignKafka(List<OrderCreateDto> dtos) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

//        Ordering객체 생성하면서 OrderingDetail객체 같이 생성
        Ordering ordering = Ordering.builder()
                .memberEmail(email)
                .build();

        for (OrderCreateDto o : dtos) {
//            product서버에 feign클라이언트를 통한 api요청 조회
            ProductDto productDto = productFeign.getProductById(o.getProductId());

            int quantity = o.getProductCount();
            if (productDto.getStockQuantity() < quantity) {
                throw new IllegalArgumentException("재고부족");
            } else {
//                재고감소 api요청을 product서버에 보내야함 ->kafka에 메시지 발행
//                productFeign.updateProductStock(ProductUpdateStockDto.builder().productId(o.getProductId()).productQuantity(o.getProductCount()) .build());
                ProductUpdateStockDto dto = ProductUpdateStockDto.builder()
                        .productId(o.getProductId())
                        .productQuantity(o.getProductCount())
                        .build();
                kafkaTemplate.send("update-stock-topic", dto);
            }

////            동시성이슈를 고려한 코드
////            redis를 통한 재고관리 및 재고잔량 확인
//            int newQuantity = stockInventoryService.decreaseStock(product.getId(), quantity);
//            if (newQuantity < 0) {
//                throw new IllegalArgumentException("재고부족");
//            }
////            rdb동기화(rabbitmq)
//            StockRabbitDto stockRabbitDto = StockRabbitDto.builder().productId(product.getId()).productCount(quantity).build();;
//            stockRabbitmqService.puslish(stockRabbitDto);

            OrderDetail orderDetail = OrderDetail.builder()
                    .ordering(ordering)
                    .productId(o.getProductId())
                    .quantity(o.getProductCount())
                    .build();
            ordering.getOrderDetails().add(orderDetail);
        }
        Ordering ordering1 = orderingRepository.save(ordering);

        return ordering;
    }






    public List<OrderListResDto> findAll() {
        List<Ordering> orderingList = orderingRepository.findAll();
        List<OrderListResDto> orderListResDtos = new ArrayList<>();
        for (Ordering o : orderingList) {
            orderListResDtos.add(o.fromEntity());
        }
        return orderListResDtos;
    }

    public List<OrderListResDto> myOrders() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        List<OrderListResDto> orderListResDtoList = new ArrayList<>();
        for (Ordering o : orderingRepository.findByMemberEmail(email)) {
            orderListResDtoList.add(o.fromEntity());
        }
        return orderListResDtoList;
    }

    public Ordering orderCancel(Long id) {
        Ordering ordering = orderingRepository.findById(id).orElseThrow(()->new EntityNotFoundException("order is not found"));
        ordering.orderCancel();
        return ordering;
    }
}
