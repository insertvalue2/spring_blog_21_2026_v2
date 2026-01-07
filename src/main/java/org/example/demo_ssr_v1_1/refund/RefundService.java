package org.example.demo_ssr_v1_1.refund;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.demo_ssr_v1_1._core.errors.exception.Exception400;
import org.example.demo_ssr_v1_1._core.errors.exception.Exception403;
import org.example.demo_ssr_v1_1._core.errors.exception.Exception404;
import org.example.demo_ssr_v1_1.payment.Payment;
import org.example.demo_ssr_v1_1.payment.PaymentRepository;
import org.example.demo_ssr_v1_1.user.User;
import org.example.demo_ssr_v1_1.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 환불 서비스
 *
 * 학생들을 위한 핵심 포인트:
 * 1. @Transactional의 역할과 범위 이해하기
 * 2. 외부 API(포트원) 연동 시 예외 처리 전략
 * 3. 비즈니스 로직의 순서 (검증 -> 외부 요청 -> DB 반영)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundRequestRepository refundRequestRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;

    // 포트원 API 설정 (application.yml)
    @Value("${portone.imp-key}")
    private String impKey;

    @Value("${portone.imp-secret}")
    private String impSecret;


    /**
     * 0단계: 환불 요청 화면 진입 시 검증 (Controller 로직 이관)
     *
     * 컨트롤러에 있던 복잡한 검증 로직을 서비스로 가져왔습니다.
     * 컨트롤러는 "요청을 받고 응답을 주는" 역할에 집중해야 합니다.
     */
    @Transactional(readOnly = true)
    public Payment 환불요청화면검증(Long paymentId, Long userId) {
        // 1. 결제 내역 조회 (User 정보 함께 조회) // paymentRepository - findByIdWithUser 만들어야 함
        Payment payment = paymentRepository.findByIdWithUser(paymentId)
                .orElseThrow(() -> new Exception404("결제 내역을 찾을 수 없습니다"));

        // 2. 본인 확인
        if (!payment.getUser().getId().equals(userId)) {
            throw new Exception403("본인의 결제 내역만 환불 요청할 수 있습니다");
        }

        // 3. 결제 완료 상태인지 확인 (paid 상태만 환불 가능)
        if (!"paid".equals(payment.getStatus())) {
            throw new Exception400("결제 완료된 건만 환불 요청할 수 있습니다");
        }

        // 4. 이미 환불 요청이 진행 중인지 확인
        if (refundRequestRepository.findByPaymentId(paymentId).isPresent()) {
            throw new Exception400("이미 환불 요청이 진행 중입니다. 결과 처리를 기다려주세요.");
        }

        return payment;
    }


    /**
     * 1단계: 사용자가 환불 요청하기
     */
    @Transactional
    public void 환불요청(Long userId, RefundResponse.RequestDTO requestDTO) {
        // 1. 유효성 검사
        requestDTO.validate();

        // 2. 화면 검증 로직 재사용 (중복 코드 제거)
        Payment payment = 환불요청화면검증(requestDTO.getPaymentId(), userId);

        // 3. 사용자 조회 (영속성 컨텍스트 로딩)
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new Exception404("사용자를 찾을 수 없습니다"));

        // 4. 환불 요청 저장
        RefundRequest refundRequest = RefundRequest.builder()
                .user(user)
                .payment(payment)
                .reason(requestDTO.getReason())
                .build();

        refundRequestRepository.save(refundRequest);
        log.info("환불 요청 저장 완료: userId={}, paymentId={}", userId, payment.getId());
    }

    /**
     * 사용자의 환불 요청 목록 조회 (세션 로그인 본인)
     */
    @Transactional(readOnly = true)
    public List<RefundResponse.ListDTO> 환불요청목록조회(Long userId) {
        List<RefundRequest> refundList = refundRequestRepository.findAllByUserId(userId);
        return refundList.stream()
                .map(RefundResponse.ListDTO::new)
                .collect(Collectors.toList());
    }

    /**
     * 3단계: 관리자가 환불 요청 목록 조회 (전체)
     */
    @Transactional(readOnly = true)
    public List<RefundResponse.AdminListDTO> 관리자환불요청목록조회() {
        List<RefundRequest> refundList = refundRequestRepository.findAllWithUserAndPayment();
        return refundList.stream()
                .map(RefundResponse.AdminListDTO::new)
                .toList();
    }



}