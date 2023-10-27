package com.surf.quiz.service;


import com.surf.quiz.dto.MemberDto;
import com.surf.quiz.dto.QuestionDto;
import com.surf.quiz.dto.UserDto;
import com.surf.quiz.dto.request.CreateRoomRequestDto;
import com.surf.quiz.dto.request.SearchMemberRequestDto;
import com.surf.quiz.dto.response.SearchMemberResponseDto;
import com.surf.quiz.entity.Quiz;
import com.surf.quiz.entity.QuizRoom;
import com.surf.quiz.repository.QuizRepository;
import com.surf.quiz.repository.QuizRoomRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class QuizRoomService {
    private final QuizRoomRepository quizRepo;
    private final QuizRepository quizRepository;
    private final SimpMessagingTemplate messageTemplate; // 메시지 브로커를 통해 클라이언트와 서버 간의 실시간 메시지 교환을 처리
//    private final QuizService quizService;
//    private final QuizRepository quizRepository;

    // 일정한 시간 간격으로 작업(태스크)를 실행하거나 지연 실행할 수 있는 스레드 풀 기반의 스케줄링 서비스
    //단일 스레드로 동작하는 스케줄링 서비스(ScheduledExecutorService) 객체를 생성하고, 이 객체(scheduler)에 대한 참조를 유지
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public QuizRoomService(QuizRoomRepository quizRepo, SimpMessagingTemplate messageTemplate, QuizRepository quizRepository) {
        this.quizRepo = quizRepo;
        this.messageTemplate = messageTemplate;
        this.quizRepository = quizRepository;
    }


    public QuizRoom save(QuizRoom room) {
        return quizRepo.save(room);
    }

    public void delete(QuizRoom room) {
        quizRepo.delete(room);
    }

    public Optional<QuizRoom> findById(Long Id) {
        return quizRepo.findById(Id);
    }

    public List<QuizRoom> findAll() {

        return quizRepo.findAll();
    }

    public List<MemberDto> getUsersByRoomId(Long roomId) {
        Optional<QuizRoom> optional = quizRepo.findById(roomId);
        if (optional.isPresent()) {
            return optional.get().getUsers();
        } else {
            throw new NoSuchElementException("No QuizRoom for ID: " + roomId);
        }
    }


    public void findAllAndSend() {
        List<QuizRoom> roomList = this.findAll();
        scheduler.schedule(() -> messageTemplate.convertAndSend("/sub/quizroom/roomList", roomList), 1, TimeUnit.SECONDS);
    }


    public SearchMemberResponseDto createSearchMemberResponseDto(SearchMemberRequestDto SearchMemberRequestDto) {
        SearchMemberResponseDto roomDto = new SearchMemberResponseDto();
        roomDto.setRoomName(SearchMemberRequestDto.getRoomName());
        roomDto.setQuizCnt(SearchMemberRequestDto.getQuizCnt());
        roomDto.setPages(SearchMemberRequestDto.getPages());
        roomDto.setSharePages(SearchMemberRequestDto.getSharePages());
        roomDto.setSingle(SearchMemberRequestDto.isSingle());

        // invite users = 초대 받은 유저 + 방 만든 유저
        // 방을 만든 사람이면 리스트의 0번으로 넣기 > 방장으로 만들기 위해서
        List<UserDto> inviteUsers = createInviteUsers();

        roomDto.setInviteUsers(inviteUsers);

        return roomDto;
    }

    private List<UserDto> createInviteUsers() {
        List<UserDto> inviteUsers = new ArrayList<>();
        inviteUsers.add(createUser(1L, "csi"));
        inviteUsers.add(createUser(2L, "isc"));

        return inviteUsers;
    }

    private UserDto createUser(Long userPk, String userName) {
        UserDto user = new UserDto();
        user.setUserPk(userPk);
        user.setUserName(userName);

        return user;
    }



    public QuizRoom createAndSaveQuizRoom(CreateRoomRequestDto createRoomRequestDto) {
        QuizRoom createQuizRoom = QuizRoom.builder()
                .roomName(createRoomRequestDto.getRoomName())
                .quizCnt(createRoomRequestDto.getQuizCnt())
                .createdDate(LocalDateTime.now())
                .sharePages(createRoomRequestDto.getSharePages())
                .pages(createRoomRequestDto.getPages())
                .single(createRoomRequestDto.isSingle())
                .inviteUsers(createRoomRequestDto.getInviteUsers())
                .build();

        // 싱글 모드면 roomMax 1 / 그룹 모드면 초대 인원 수
        if (createQuizRoom.isSingle()) {
            createQuizRoom.setRoomMax(1);
        } else {
            createQuizRoom.setRoomMax(createRoomRequestDto.getInviteUsers().size());
        }

        QuizRoom createdQuizroom = this.save(createQuizRoom);

        // 퀴즈 생성
        Quiz quiz = this.createQuiz(createdQuizroom.getId().intValue());
        quizRepository.save(quiz);

        // 스레드 스케줄러
        this.findAllAndSend();

        return createdQuizroom;
    }




    public void changeReady(Long roomId, Map<String, Object> payload) {
        Optional<QuizRoom> optionalQuizRoom = this.findById(roomId);
        QuizRoom quizRoom = optionalQuizRoom.orElseThrow();

        // userpk , 레디 받기
        Long id = ((Number) payload.get("userPk")).longValue();
        String action = (String) payload.get("action");

        if (action.equals("ready")) {
            // 레디 처리
            quizRoom.memberReady(id, action);
            quizRoom.setReadyCnt(quizRoom.getReadyCnt() + 1);
            this.save(quizRoom);
            messageTemplate.convertAndSend("/sub/quizroom/detail/" + roomId, quizRoom);

        } else if (action.equals("unready")) {
            // 언레디 처리
            quizRoom.memberReady(id, action);
            quizRoom.setReadyCnt(quizRoom.getReadyCnt() - 1);
            this.save(quizRoom);
            messageTemplate.convertAndSend("/sub/quizroom/detail/" + roomId, quizRoom);
        }

        // 스레드 스케줄러
        this.findAllAndSend();
    }



    public void enterQuizRoom(Long roomId, MemberDto memberDto ) {
        Optional<QuizRoom> optionalQuizRoom = findById(roomId);
        QuizRoom quizRoom = optionalQuizRoom.orElseThrow();

        List<MemberDto> members = quizRoom.getUsers();

        // 초대 유저에 없으면 입장 X
        if (quizRoom.getInviteUsers().stream().noneMatch(user -> Objects.equals(user.getUserPk(), memberDto.getUserPk()))) {
            return;
        }

        // 멤버가 있는데 들어가려는 유저가 이미 방에 있으면 리턴
        if (members != null) {
            for (MemberDto member : members) {
                if (memberDto.getUserPk().equals(member.getUserPk())) {
                    return;
                }
            }
        }

        // 방에 입장
        quizRoom.memberIn(memberDto);

        quizRoom.setRoomCnt(quizRoom.getRoomCnt() + 1);
        this.save(quizRoom);
        messageTemplate.convertAndSend("/sub/quizroom/detail/" + roomId, quizRoom);

        this.findAllAndSend();
    }


    public void leaveQuizRoom(Long roomId, MemberDto memberDto) {
        Optional<QuizRoom> optionalQuizRoom = findById(roomId);
        QuizRoom quizRoom = optionalQuizRoom.orElseThrow();

        Optional<MemberDto> optionalMember = quizRoom.getUsers().stream()
                .filter(member -> member.getUserPk().equals(memberDto.getUserPk()))
                .findFirst();

        if (optionalMember.isEmpty()) {
            return;
        }

        MemberDto member = optionalMember.get();

        if (member.isReady()) {
            quizRoom.setReadyCnt(quizRoom.getReadyCnt() - 1);
        }

        quizRoom.memberOut(memberDto);
        quizRoom.setRoomCnt(quizRoom.getRoomCnt() - 1);

        if (quizRoom.getUsers().isEmpty()) {
            delete(quizRoom);
        } else {
            if (memberDto.isHost()) {
                quizRoom.getUsers().get(0).setHost(true);
                messageTemplate.convertAndSend("/sub/quizroom/detail/" + roomId, quizRoom);
            } else {
                messageTemplate.convertAndSend("/sub/quizroom/detail/" + roomId, quizRoom);
            }
        }

        this.save(quizRoom);
        this.findAllAndSend();
    }



    public Quiz createQuiz(int roomId) {
        Optional<Quiz> optionalQuiz = quizRepository.findByRoomId(roomId);
        if(optionalQuiz.isPresent()) {
            return null;
//            throw new AlreadyExistsException("Quiz already exists for room: " + roomId);
        }
        // 퀴즈 생성
        Quiz quiz = new Quiz();

        quiz.setRoomId(roomId);
        quiz.setCreatedDate(LocalDateTime.now());
        // 문제 생성
        QuestionDto question1 = new QuestionDto();

        question1.setQuestion("IP(Internet Protocol) 주소는 어떻게 구성되며, 어떤 역할을 담당하고 있나요?");
        question1.setId(1);
        // 보기 생성
        List<String> examples = new ArrayList<>();
        examples.add("1) IP 주소는 64비트로 구성되어 있으며, 데이터의 무결성을 검증한다.");
        examples.add("2) IP 주소는 4바이트로 이루어져 있으며, 컴퓨터의 주소 역할을 한다.");
        examples.add("3) IP 주소는 데이터의 재조합을 처리하며, 데이터의 순서를 조정한다.");
        examples.add("4) IP 주소는 하드웨어 고유의 식별번호인 MAC 주소와 동일하다.");

        question1.setExample(examples); // 변경된 부분


        // 문제에 답안 설정
        question1.setAnswer("2) IP 주소는 4바이트로 이루어져 있으며, 컴퓨터의 주소 역할을 한다.");


        QuestionDto question2 = new QuestionDto();
        question2.setQuestion("TCP/IP는 어떤 두 가지 프로토콜로 구성되어 있으며, 어떤 역할을 각각 수행하고 있는가?");
        question2.setId(2);
        List<String> examples2 = new ArrayList<>();
        examples2.add("1) TCP가 데이터의 추적을 처리하고, IP가 데이터의 배달을 담당한다.");
        examples2.add("2) IP가 데이터의 추적을 처리하고, TCP가 데이터의 배달을 담당한다.");
        examples2.add("3) TCP와 IP가 모두 데이터의 재조합을 처리한다.");
        examples2.add("4) TCP와 IP가 모두 데이터의 손실 여부를 확인한다.");
        question2.setExample(examples2);
        question2.setAnswer("1) TCP가 데이터의 추적을 처리하고, IP가 데이터의 배달을 담당한다.");

        // 퀴즈에 문제 추가
        List<QuestionDto> questions = new ArrayList<>();
        questions.add(question1);
        quiz.setQuestion(questions);
        quiz.getQuestion().add(question2);

        // 퀴즈 완료 상태 설정
        quiz.setComplete(false);
        return quiz;
    }

}
