package org.example.repository;

import jakarta.persistence.LockModeType;
import org.example.dto.FoodDto;
import org.example.entity.Food;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface FoodRepository extends JpaRepository<Food, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Food f where f.id = :id")
    Optional<Food> findByIdForUpdate(long id);

    @Modifying
    @Query("""
            update Food f set f.stockQuantity = f.stockQuantity - :quantity
            where f.id = :id and f.status = 'ACTIVE' and f.stockQuantity >= :quantity
            """)
    int decreaseStock(long id, int quantity);

    @Modifying
    @Query("""
            update Food f set f.stockQuantity = f.stockQuantity + :quantity
            where f.id = :id and f.stockQuantity <= :maxBeforeRestore
            """)
    int increaseStock(long id, int quantity, int maxBeforeRestore);

    // Read the current row rather than a transaction snapshot or a managed Food entity.
    @Query(value = """
            SELECT id, category_id AS categoryId, name, description, price, image_url AS imageUrl,
                   stock_quantity AS stockQuantity, status
            FROM foods WHERE id = :id FOR UPDATE
            """, nativeQuery = true)
    Optional<FoodSnapshot> readForStockUpdate(long id);

    interface FoodSnapshot {
        Long getId();
        Long getCategoryId();
        String getName();
        String getDescription();
        BigDecimal getPrice();
        String getImageUrl();
        Integer getStockQuantity();
        String getStatus();

        default FoodDto toDto() {
            return new FoodDto(getId(), getCategoryId(), getName(), getDescription(), getPrice(),
                    getImageUrl(), getStockQuantity(), getStatus());
        }
    }

    @Query("""
            select f from Food f
            where f.status = 'ACTIVE' and (:categoryId is null or f.categoryId = :categoryId)
            order by f.id""")
    List<Food> findActive(Long categoryId);

    @Query("""
            select f from Food f
            where f.status = 'ACTIVE' and (:categoryId is null or f.categoryId = :categoryId)
              and (lower(f.name) like lower(concat('%', :keyword, '%')) escape '!'
                   or lower(f.description) like lower(concat('%', :keyword, '%')) escape '!')
            order by f.id""")
    List<Food> searchActive(String keyword, Long categoryId);

    boolean existsByName(String name);
    boolean existsByNameAndIdNot(String name, Long id);
}
