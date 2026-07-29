package ru.partsflow.sales;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByDealIdOrderByIdDesc(Long dealId);

    List<Payment> findByCustomerIdOrderByIdDesc(Long customerId);
}
