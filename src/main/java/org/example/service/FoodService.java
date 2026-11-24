package org.example.service;

import lombok.RequiredArgsConstructor;
import org.example.common.BusinessException;
import org.example.dto.FoodDto;
import org.example.dto.FoodRequest;
import org.example.entity.Food;
import org.example.repository.CategoryRepository;
import org.example.repository.FoodRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FoodService {

    private static final String ACTIVE = "ACTIVE";

    private final FoodRepository foodRepository;
    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<FoodDto> getActiveFoods(Long categoryId) {
        if (categoryId != null) requireCategory(categoryId);
        return foodRepository.findActive(categoryId).stream().map(FoodDto::fromEntity).toList();
    }

    @Transactional(readOnly = true)
    public FoodDto getFoodById(Long id) {
        Food food = findFood(id);
        if (!ACTIVE.equals(food.getStatus())) {
            throw foodNotFound();
        }
        return FoodDto.fromEntity(food);
    }

    @Transactional(readOnly = true)
    public List<FoodDto> searchFoods(String keyword, Long categoryId) {
        if (keyword == null || keyword.trim().isEmpty() || keyword.trim().length() > 100) {
            throw BusinessException.invalid("VALIDATION_ERROR", "Từ khóa phải có từ 1 đến 100 ký tự.");
        }

        if (categoryId != null) requireCategory(categoryId);
        return foodRepository.searchActive(escapeLike(keyword.trim()), categoryId).stream()
                .map(FoodDto::fromEntity).toList();
    }

    @Transactional
    public FoodDto createFood(FoodRequest request) {
        requireCategory(request.categoryId());

        String name = request.name().trim();
        if (foodRepository.existsByName(name)) {
            throw duplicateName();
        }

        Food food = Food.builder()
                .categoryId(request.categoryId())
                .name(name)
                .description(request.description())
                .price(request.price())
                .imageUrl(request.imageUrl())
                .stockQuantity(request.stockQuantity() != null ? request.stockQuantity() : 0)
                .status(request.status() != null ? request.status() : ACTIVE)
                .build();
        return FoodDto.fromEntity(foodRepository.save(food));
    }

    @Transactional
    public FoodDto updateFood(Long id, FoodRequest request) {
        Food food = foodRepository.findByIdForUpdate(id).orElseThrow(FoodService::foodNotFound);
        requireCategory(request.categoryId());

        String name = request.name().trim();
        if (foodRepository.existsByNameAndIdNot(name, id)) {
            throw duplicateName();
        }

        food.setCategoryId(request.categoryId());
        food.setName(name);
        food.setDescription(request.description());
        food.setPrice(request.price());
        food.setImageUrl(request.imageUrl());
        if (request.stockQuantity() != null) food.setStockQuantity(request.stockQuantity());
        if (request.status() != null) food.setStatus(request.status());
        return FoodDto.fromEntity(food);
    }

    @Transactional
    public void deleteFood(Long id) {
        foodRepository.findByIdForUpdate(id).orElseThrow(FoodService::foodNotFound).setStatus("INACTIVE");
    }


    @Transactional(readOnly = true)
    public FoodDto getAvailableFood(long id) {
        Food food = findFood(id);
        requireActive(food);
        return FoodDto.fromEntity(food);
    }

    @Transactional(readOnly = true)
    public Map<Long, FoodDto> findByIds(Collection<Long> ids) {
        return foodRepository.findAllById(ids).stream()
                .map(FoodDto::fromEntity)
                .collect(Collectors.toMap(FoodDto::id, Function.identity()));
    }


    @Transactional
    public FoodDto reserveStock(long id, int quantity) {
        requirePositiveQuantity(quantity);
        int updated = foodRepository.decreaseStock(id, quantity);
        var food = foodRepository.readForStockUpdate(id).orElseThrow(FoodService::foodNotFound);
        if (updated == 1) return food.toDto();
        requireActive(food.getStatus(), food.getName());
        if (food.getStockQuantity() < quantity) {
            throw BusinessException.conflict("INSUFFICIENT_STOCK", "Món \"" + food.getName() + "\" không đủ tồn kho.");
        }
        throw BusinessException.conflict("CONCURRENT_UPDATE", "Tồn kho vừa được cập nhật, vui lòng thử lại.");
    }

    @Transactional
    public void releaseStock(long id, int quantity) {
        requirePositiveQuantity(quantity);
        if (foodRepository.increaseStock(id, quantity, Integer.MAX_VALUE - quantity) == 1) return;
        foodRepository.readForStockUpdate(id).orElseThrow(FoodService::foodNotFound);
        throw BusinessException.conflict("STOCK_RESTORE_CONFLICT", "Không thể hoàn số lượng món vào tồn kho.");
    }

    private static void requirePositiveQuantity(int quantity) {
        if (quantity <= 0) throw BusinessException.invalid("VALIDATION_ERROR", "Số lượng phải lớn hơn 0.");
    }

    private Food findFood(long id) {
        return foodRepository.findById(id).orElseThrow(FoodService::foodNotFound);
    }

    private void requireCategory(Long categoryId) {
        if (!categoryRepository.existsById(categoryId)) {
            throw BusinessException.notFound("CATEGORY_NOT_FOUND", "Không tìm thấy danh mục.");
        }
    }

    private static void requireActive(Food food) {
        requireActive(food.getStatus(), food.getName());
    }

    private static void requireActive(String status, String name) {
        if (!ACTIVE.equals(status)) {
            throw BusinessException.conflict("FOOD_UNAVAILABLE", "Món \"" + name + "\" đã ngừng bán.");
        }
    }

    private static BusinessException foodNotFound() {
        return BusinessException.notFound("FOOD_NOT_FOUND", "Không tìm thấy món ăn.");
    }

    private static BusinessException duplicateName() {
        return BusinessException.conflict("FOOD_NAME_CONFLICT", "Tên món ăn đã tồn tại trong thực đơn.");
    }

    private static String escapeLike(String keyword) {
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
