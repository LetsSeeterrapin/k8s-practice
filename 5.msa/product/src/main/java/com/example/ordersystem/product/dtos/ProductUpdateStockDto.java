package com.example.ordersystem.product.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductUpdateStockDto {
    private Long productId;
    private Integer productQuantity;
}
