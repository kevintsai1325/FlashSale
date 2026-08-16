# Task 2 Report — Capture catalog name during order creation

## Status

COMPLETED

## RED/GREEN evidence

- Added `OrderPurchaseConsumerTest.savesCatalogNameAsOrderItemSnapshotWhenProcessingPurchaseEvent` before changing production code. The test exercises the real consumer and real `Order` aggregate while mocking only repository and metrics/message-guard boundaries.
- The repository already contained Task 1's catalog-lookup implementation (`ProductRepository.findById` and `product.getName()` passed to `Order.createPendingPayment`), so the initial test run was green.
- Mutation RED: temporarily replaced the consumer's `product.getName()` argument with `null`; `./gradlew.bat test --tests com.flashsale.order.adapter.messaging.OrderPurchaseConsumerTest` failed with `AssertionFailedError` at `OrderPurchaseConsumerTest.java:77` (1 test, 1 failed).
- Restored `product.getName()` and re-ran `./gradlew.bat test --tests com.flashsale.order.adapter.messaging.OrderPurchaseConsumerTest`: `BUILD SUCCESSFUL` (1 test passing).

## Changes

- Added a mock-boundary unit test for `OrderPurchaseConsumer`.
- The test serializes a real purchase event, supplies a real catalog `Product` named `限量鍵盤`, captures the real `Order` passed through the save boundary, and asserts its persisted item model contains `productName == "限量鍵盤"`.
- No production change was necessary in this task: Task 1's existing consumer wiring and lookup satisfy the behavior.

## Commit

- `69865ae test: verify consumer captures product name snapshots`

## Self-review

- The test catches omission, `null`, or another incorrect value passed as the order-item name while keeping the domain aggregate real.
- It avoids mock-interaction assertions; the assertion targets the saved aggregate's actual order item snapshot.
- It does not run an integration test, per the user constraint. Database-column mapping/persistence is intentionally outside this unit-test scope and remains an integration-test concern.

## Concerns

- Gradle emits pre-existing toolchain warnings about bootstrap class sharing and deprecated Gradle features. They do not fail the targeted unit test.
