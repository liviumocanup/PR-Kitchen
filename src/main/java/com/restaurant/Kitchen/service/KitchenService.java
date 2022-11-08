package com.restaurant.Kitchen.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.Kitchen.models.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class KitchenService {
    public static final List<Food> foodList = new ArrayList<>();
    public static final List<Cook> cookList = loadDefaultCooks();
    public static final List<Order> orderList = new CopyOnWriteArrayList<>();

    public static final Map<Integer, List<CookingDetails>> orderToFoodListMap = new ConcurrentHashMap<>();

    private static final Integer NUMBER_OF_STOVES = 1;
    private static final Integer NUMBER_OF_OVENS = 2;

    public static final Semaphore stoveSemaphore = new Semaphore(NUMBER_OF_STOVES);
    public static final Semaphore ovenSemaphore = new Semaphore(NUMBER_OF_OVENS);

    public static BlockingQueue<Item> items = new LinkedBlockingQueue<>();

    public final static int TIME_UNIT = 50;

    protected static Map<Integer, Food> itemMap;
    private static String DINING_HALL_URL;

    @Value("${dining-hall-service.url}")
    public void setDiningHallServiceUrl(String url) {
        DINING_HALL_URL = url;
    }

    @Value("${restaurant.menu}")
    public String restaurantMenu;

    @PostConstruct
    public void loadMenu() {
        System.out.println(restaurantMenu);
        loadDefaultMenu();
    }

    public void receiveOrder(Order order) {
        order.setReceivedAt(Instant.now());
        log.info("-> Received " + order + " successfully.");
        orderList.add(order);

        List<Item> orderItems = new CopyOnWriteArrayList<>();
        for (Integer foodId : order.getItems()) {
            Food currentFood = foodList.get(foodId - 1);
            orderItems.add(new Item(foodId, order.getOrderId(), order.getPriority(), currentFood.getCookingApparatus(),
                    currentFood.getPreparationTime(), currentFood.getComplexity()));
        }

        orderItems.stream()
                .sorted(Comparator.comparingInt(Item::getPriority))
                .forEach(item -> items.add(item));
    }

    public static synchronized void checkIfOrderIsReady(Item item, int cookId) {

        Order order = orderList.stream()
                .filter(order1 -> order1.getOrderId() == item.getOrderId())
                .findFirst()
                .orElseThrow();

        orderToFoodListMap.putIfAbsent(item.getOrderId(), new ArrayList<>());
        List<CookingDetails> cookingDetails = orderToFoodListMap.get(item.getOrderId());
        cookingDetails.add(new CookingDetails(item.getMenuId(), cookId));
        if (cookingDetails.size() == order.getItems().size()) {
            sendFinishedOrderBackToKitchen(order);
        }
    }

    private static void sendFinishedOrderBackToKitchen(Order order) {
        Long cookingTime = (Instant.now().getEpochSecond() - order.getReceivedAt().getEpochSecond());
        FinishedOrder finishedOrder = new FinishedOrder(order, cookingTime, orderToFoodListMap.get(order.getOrderId()));

        orderToFoodListMap.remove(order.getOrderId());

        RestTemplate restTemplate = new RestTemplate();

        ResponseEntity<Void> response = restTemplate.postForEntity(DINING_HALL_URL, finishedOrder, Void.class);
        if (response.getStatusCode() != HttpStatus.ACCEPTED) {
            log.error("<!!!!!> " + order + " was unsuccessful (couldn't be sent back to dining hall service).");
        } else {
            log.info("<- " + finishedOrder + " was sent back to Dining Hall successfully.");
        }
    }

    public Double getEstimatedPrepTimeForOrderById(Integer orderId) {
        List<CookingDetails> foodDetails = orderToFoodListMap.get(orderId);
        Optional<Order> orderOptional = orderList.stream()
                .filter(order1 -> Objects.equals(order1.getOrderId(), orderId))
                .findFirst();

        if (foodDetails != null && orderOptional.isPresent()) {
            Order order = orderOptional.get();
            int B = cookList.stream().mapToInt(Cook::getProficiency).sum();
            List<Integer> cookedItemsIds = orderToFoodListMap.get(orderId).stream()
                    .map(CookingDetails::getFoodId)
                    .collect(Collectors.toList());
            List<Food> itemsNotReady = order.getItems().stream()
                    .filter(i -> !cookedItemsIds.contains(i))
                    .map(this::getItemById)
                    .collect(Collectors.toList());

            double A = 0, C = 0;

            if (itemsNotReady.isEmpty())
                return 0D;

            for (Food item : itemsNotReady) {
                if (item.getCookingApparatus() == null) {
                    A += item.getPreparationTime();
                } else {
                    C += item.getPreparationTime();
                }
            }

            int D = NUMBER_OF_OVENS + NUMBER_OF_STOVES;

            double E = items.size();
            int F = itemsNotReady.size();

            return (A / B + C / D) * (E + F) / F;
        } else return 0D;
    }

    private Food getItemById(Integer id){
        return itemMap.get(id);
    }

    private void loadDefaultMenu() {
        ObjectMapper mapper = new ObjectMapper();
        System.out.println(restaurantMenu);
        InputStream is = KitchenService.class.getResourceAsStream("/"+restaurantMenu);
        try {
            List<Food> f = mapper.readValue(is, new TypeReference<>() {});
            itemMap = new HashMap<>();
            for (Food food : f){
                itemMap.put(food.getId(), food);
                foodList.add(food);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Cook> loadDefaultCooks() {
        ObjectMapper mapper = new ObjectMapper();
        InputStream is = KitchenService.class.getResourceAsStream("/cooks.json");
        try {
            return mapper.readValue(is, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
