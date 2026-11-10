// 기획서 워드 파일 생성기
// 사용법: node build.js  → 모유재고관리_기획서.docx 생성
// 구현 중 설계가 바뀌면 이 파일의 내용을 고치고, 맨 아래 CHANGELOG에 한 줄 추가한 뒤 다시 실행한다.

const fs = require("fs");
const path = require("path");
const {
  Document, Packer, Paragraph, TextRun, HeadingLevel, Table, TableRow, TableCell,
  WidthType, ShadingType, AlignmentType, BorderStyle, LevelFormat, Footer, Header,
  PageNumber, PageBreak, VerticalAlign,
} = require("docx");

// ───────────────────────── 문서 정보 ─────────────────────────
const TITLE = "신생아실 모유 재고관리 앱";
const SUBTITLE = "기획서 (프로토타입)";

// 변경 이력: 위가 최신. 버전과 날짜는 첫 줄을 표지에 사용한다.
const CHANGELOG = [
  ["0.6", "2026-11-10", "2-1 서버 보강 개발 완료: HTTPS 전용 서버와 서버 등록 QR, DB 구조 버전 관리와 자동 백업, 직원 로그인 5회 실패 잠금"],
  ["0.5", "2026-11-10", "2단계를 2-1 서버 보강과 2-2 간호사 앱으로 나눔. 병원 기기는 서버 등록 QR을 한 번 찍어 서버를 등록(인증서 고정)"],
  ["0.4", "2026-11-10", "1단계 서버 기초 개발 완료 후 검토 반영: 직원 로그인 5회 실패 잠금, 실사용 전 DB 구조 버전 관리(Alembic) 도입을 2단계로 앞당김"],
  ["0.3", "2026-11-10", "보호자 조회를 병원 기기에서만 하도록 변경. 재고 부족 알림을 문자 대신 앱 알림으로 변경(직원 기기 알림 기본, 보호자 휴대폰 푸시는 병원 허용 시 선택). 기본 기능은 외부 인터넷 연결 불필요"],
  ["0.2", "2026-11-10", "퇴원 환자 개인정보 말소 추가: 퇴원 처리는 관리자만, 퇴원 즉시 보호자 로그인 중단, 30일 뒤 자동 말소(관리자 즉시 말소 가능)"],
  ["0.1", "2026-11-10", "최초 작성. 프로토타입 데이터베이스를 SQLite로 결정 (설치·관리 간소화)"],
];

// ───────────────────────── 스타일 도우미 ─────────────────────────
const FONT = "Malgun Gothic";
const F = { ascii: FONT, eastAsia: FONT, hAnsi: FONT, cs: FONT };
const CONTENT_W = 9638; // A4(11906) - 좌우 여백 1134*2
const ACCENT = "1F4E79";
const HEAD_FILL = "DCE6F2";
const SOFT_FILL = "F2F6FA";

const run = (text, opts = {}) => new TextRun({ text, font: F, ...opts });

const p = (text, opts = {}) =>
  new Paragraph({ spacing: { after: 120, line: 300 }, ...opts, children: [run(text, opts.run || {})] });

const h1 = (text) => new Paragraph({ heading: HeadingLevel.HEADING_1, children: [run(text)] });
const h2 = (text) => new Paragraph({ heading: HeadingLevel.HEADING_2, children: [run(text)] });

// "굵은부분: 나머지" 형태면 앞부분을 굵게
const richRuns = (text) => {
  const m = text.match(/^\*\*(.+?)\*\*(.*)$/);
  return m ? [run(m[1], { bold: true }), run(m[2])] : [run(text)];
};

const bullet = (text, level = 0) =>
  new Paragraph({ numbering: { reference: "bullets", level }, spacing: { after: 60, line: 290 }, children: richRuns(text) });

const bullets = (items) =>
  items.flatMap((it) => (Array.isArray(it) ? it.map((s) => bullet(s, 1)) : [bullet(it)]));

const border = { style: BorderStyle.SINGLE, size: 4, color: "A6B8CC" };
const borders = { top: border, bottom: border, left: border, right: border };

const cell = (content, width, { fill, bold = false, align = AlignmentType.LEFT } = {}) =>
  new TableCell({
    width: { size: width, type: WidthType.DXA },
    borders,
    shading: fill ? { type: ShadingType.CLEAR, fill, color: "auto" } : undefined,
    margins: { top: 80, bottom: 80, left: 120, right: 120 },
    verticalAlign: VerticalAlign.CENTER,
    children: [].concat(content).map(
      (line) => new Paragraph({ alignment: align, spacing: { after: 0, line: 280 }, children: [run(line, { bold, size: 20 })] })
    ),
  });

// 표: headers = 머리글 배열(또는 null), rows = 행 배열, widths = 열 너비(합계 CONTENT_W)
const table = (headers, rows, widths, { firstColBold = true } = {}) => {
  const sum = widths.reduce((a, b) => a + b, 0);
  if (sum !== CONTENT_W) throw new Error(`열 너비 합계 ${sum} != ${CONTENT_W}`);
  const trs = [];
  if (headers) {
    trs.push(new TableRow({
      tableHeader: true,
      children: headers.map((h, i) => cell(h, widths[i], { fill: HEAD_FILL, bold: true, align: AlignmentType.CENTER })),
    }));
  }
  rows.forEach((r) =>
    trs.push(new TableRow({
      children: r.map((c, i) => cell(c, widths[i], i === 0 && firstColBold ? { fill: SOFT_FILL, bold: true } : {})),
    }))
  );
  return new Table({ width: { size: CONTENT_W, type: WidthType.DXA }, columnWidths: widths, rows: trs });
};

const gap = () => new Paragraph({ spacing: { after: 120 }, children: [] });

// ───────────────────────── 본문 ─────────────────────────
const [VERSION, DATE] = CHANGELOG[0];

const cover = [
  new Paragraph({ spacing: { before: 3200, after: 200 }, alignment: AlignmentType.CENTER, children: [run(TITLE, { size: 48, bold: true, color: ACCENT })] }),
  new Paragraph({ spacing: { after: 1600 }, alignment: AlignmentType.CENTER, children: [run(SUBTITLE, { size: 32, color: "404040" })] }),
  table(null, [
    ["문서 버전", `v${VERSION}`],
    ["작성일", DATE],
    ["단계", "프로토타입 (채택 시 병원 규모에 맞춰 안정화·확장)"],
    ["작성 부서 / 작성자", "한성대학교/김민서"],
  ], [2600, 7038]),
  new Paragraph({ children: [new PageBreak()] }),
];

const sec1 = [
  h1("1. 개요"),
  table(null, [
    ["사업명", "신생아실 모유 재고관리 앱 (프로토타입)"],
    ["대상", ["신생아 환자 약 59명과 그 보호자", "신생아실 간호사 및 관리자"]],
    ["목적", "보호자가 얼려 오는 모유를 팩 단위로 입고·사용·잔량 관리하고, 재고가 부족해지면 앱 알림으로 알린다"],
    ["형태", "병원 기기(갤럭시탭·갤럭시폰)용 안드로이드 앱 + 병원 내부 서버. 기본 기능은 외부 인터넷 연결 없이 동작"],
    ["현재 단계", "프로토타입. 병원에 채택되면 규모에 맞춰 데이터베이스 등 기술 구성을 바꿔 안정화한다"],
  ], [2200, 7438]),
];

const sec2 = [
  h1("2. 추진 배경 및 필요성"),
  h2("2.1 현황"),
  ...bullets([
    "신생아 환자 약 59명의 보호자가 모유를 여러 차례 유축해 얼려서 가져온다. 한 번에 2~5팩씩 들어온다.",
    "아기마다 입고 시점과 먹는 양이 달라, 환자별 남은 양을 계속 따로 추적해야 한다.",
    "재고가 떨어지기 전에 보호자가 모유를 다시 가져와야 아기에게 끊김 없이 먹일 수 있다.",
  ]),
  h2("2.2 현재 방식에서 겪기 쉬운 문제"),
  table(["문제", "내용"], [
    ["재고 파악 부담", "59명분 재고를 환자별로 세고 기억해야 하며, 근무 교대 때 인수인계가 누락될 수 있다"],
    ["보호자 연락 지연", "재고가 떨어졌는지 직원이 확인하고 직접 연락해야 해서 연락이 늦거나 빠질 수 있다"],
    ["투여·기록 오류 위험", "비슷한 이름이나 병상 때문에 다른 아기의 모유로 잘못 기록·사용할 위험이 있다"],
    ["기록 추적 어려움", "누가 언제 입고·사용·폐기했는지 나중에 확인하기 어렵다"],
    ["통계 부재", "일별 사용량과 폐기량을 집계하기 어려워 운영 개선의 근거가 부족하다"],
  ], [2600, 7038]),
  h2("2.3 필요성"),
  p("모유 재고를 전산으로 관리하면 여러 기기에서 같은 재고를 실시간으로 보고, 부족할 때 직원에게 바로 알려 보호자에게 제때 전달하며, 모든 입력을 기록으로 남길 수 있다. 이는 간호 업무 부담을 줄이고 아기에게 모유를 끊김 없이 안전하게 제공하는 데 직접 도움이 된다."),
];

const sec3 = [
  h1("3. 목표"),
  h2("3.1 최종 목표"),
  p("종이 장부나 수작업 확인 없이, 앱만으로 신생아 환자 약 59명의 모유 재고를 정확하게 관리한다."),
  h2("3.2 세부 목표와 달성 기준"),
  table(["세부 목표", "달성 기준"], [
    ["정확한 재고", "갤럭시탭·폰 여러 대에서 동시에 입력해도 환자별 잔량이 항상 일치한다"],
    ["부족 알림 자동화", "재고가 부족해지면 직원이 따로 확인하지 않아도 모든 직원 기기에 1분 안에 알림이 뜨고, 같은 부족 상황에 중복 알림이 없다"],
    ["투여·기록 오류 예방", "저장하기 전에 아기 이름과 병상을 크게 보여 주는 확인 창을 거친다"],
    ["기록 추적", "모든 입고·사용·폐기·취소에 입력한 직원과 시각이 남고, 기록은 지워지지 않는다"],
    ["운영 통계", "관리자가 환자별 현황과 일별 사용량을 바로 확인하고 내려받을 수 있다"],
  ], [2600, 7038]),
];

const sec4 = [
  h1("4. 주요 기능"),
  table(["사용자", "기능"], [
    ["간호사", ["재고 부족 앱 알림(소리·진동)과 '보호자 연락 필요' 목록, 연락 완료 기록", "환자 목록과 검색(이름·병상·등록번호), 재고 상태 색상 표시", "입고·사용·폐기 입력과 저장 전 확인 창", "잘못 입력한 기록 취소 (본인 기록, 입력 후 10분 이내)"]],
    ["관리자", ["전체 환자 재고 현황과 요약", "일별 사용량, 7일·30일 그래프, 엑셀용 파일 내려받기", "환자 등록·수정·퇴원 처리, 알림 발송 관리", "퇴원 환자 개인정보 말소 (30일 뒤 자동, 또는 즉시)", "직원 계정 관리와 알림 기준 설정"]],
    ["보호자", ["병원 기기(신생아실 조회용 태블릿)에서 자기 아기의 남은 모유와 입고·사용 기록 조회, 부족하면 '모유를 가져와 주세요' 안내", "로그인: 보호자 전화번호 + 아기 등록번호, 1분 동안 조작이 없으면 자동 로그아웃"]],
    ["서버(자동)", ["재고 부족 판정과 직원 기기 앱 알림 생성", "선택 기능: 병원이 외부 연결을 허용하면 보호자 휴대폰 푸시 알림"]],
  ], [1800, 7838]),
  h2("4.1 재고 부족 알림 기준"),
  ...bullets([
    "**사용 기록이 3일 이상인 아기: **최근 3일 하루 평균 사용량으로 계산해 남은 양이 2일치 이하가 되면 알림",
    "**새로 들어온 아기: **남은 팩이 3개 이하가 되면 알림",
    "**중복 방지: **같은 부족 상황에는 한 번만 보내고, 새로 입고되면 다시 보낼 수 있는 상태가 된다",
    "**직원 알림: **직원 기기 앱이 1분마다 서버를 확인해 소리·진동으로 알리고, 직원이 보호자에게 연락한 뒤 '연락 완료'를 누르면 기록된다",
    "**보호자 푸시(선택): **병원이 허용할 때만, 오전 8시~오후 9시에 보내며 아기 이름 등 개인정보는 넣지 않는다",
    "기준값은 관리자가 바꿀 수 있고, 아기별로 따로 정할 수도 있다",
  ]),
];

const sec5 = [
  h1("5. 기대 효과"),
  table(["구분", "기대 효과"], [
    ["업무 효율", "환자별 남은 모유를 한 화면에서 확인하므로 수작업 집계와 교대 인수인계 부담이 줄어든다"],
    ["환자 안전", "저장 전 확인 창으로 다른 아기의 모유로 잘못 기록하는 실수를 막고, 모든 기록을 추적할 수 있다"],
    ["보호자 만족", "모유가 떨어지기 전에 안내받고, 면회 때 병원 기기로 직접 재고를 확인할 수 있다"],
    ["끊김 없는 수유", "재고 부족을 직원이 바로 알고 보호자에게 미리 전달하므로 아기에게 모유를 계속 먹일 수 있다"],
    ["보안·도입 용이성", "기본 기능이 병원 내부망만으로 동작해 외부 연결 허용이나 문자 업체 계약 없이 도입할 수 있다"],
    ["운영 관리", "일별 사용량과 폐기 사유 통계로 근거에 기반해 신생아실을 운영할 수 있다"],
    ["낭비 감소", "폐기 사유(해동 후 남음, 오염 등)를 기록해 모유가 버려지는 원인을 파악하고 줄일 수 있다"],
    ["확장성", "프로토타입으로 효과를 검증한 뒤 다른 병동이나 더 큰 규모로 넓힐 수 있는 구조로 만든다"],
  ], [2200, 7438]),
];

const arrowW = 666, boxW = 1910;
const flowCell = (lines, w, isBox) =>
  new TableCell({
    width: { size: w, type: WidthType.DXA },
    borders: isBox ? { top: { style: BorderStyle.SINGLE, size: 8, color: ACCENT }, bottom: { style: BorderStyle.SINGLE, size: 8, color: ACCENT }, left: { style: BorderStyle.SINGLE, size: 8, color: ACCENT }, right: { style: BorderStyle.SINGLE, size: 8, color: ACCENT } }
      : { top: { style: BorderStyle.NONE, size: 0, color: "FFFFFF" }, bottom: { style: BorderStyle.NONE, size: 0, color: "FFFFFF" }, left: { style: BorderStyle.NONE, size: 0, color: "FFFFFF" }, right: { style: BorderStyle.NONE, size: 0, color: "FFFFFF" } },
    shading: isBox ? { type: ShadingType.CLEAR, fill: SOFT_FILL, color: "auto" } : undefined,
    margins: { top: 100, bottom: 100, left: 80, right: 80 },
    verticalAlign: VerticalAlign.CENTER,
    children: lines.map((l, i) => new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 0 }, children: [run(l, { bold: isBox && i === 0, size: isBox ? 19 : 22, color: isBox ? "000000" : ACCENT })] })),
  });

const flow = new Table({
  width: { size: CONTENT_W, type: WidthType.DXA },
  columnWidths: [boxW, arrowW, boxW, arrowW, boxW, arrowW, boxW],
  rows: [new TableRow({ children: [
    flowCell(["병원 기기 앱", "갤럭시탭·폰", "(직원 입력·알림)"], boxW, true),
    flowCell(["↔"], arrowW, false),
    flowCell(["병원 내부 서버", "API + DB", "(내부망, HTTPS)"], boxW, true),
    flowCell(["↔"], arrowW, false),
    flowCell(["조회용 태블릿", "신생아실", "(보호자 조회)"], boxW, true),
    flowCell(["┄"], arrowW, false),
    flowCell(["보호자 휴대폰", "푸시 알림", "(선택, 병원 허용 시)"], boxW, true),
  ] })],
});

const sec6 = [
  h1("6. 시스템 구성 (프로토타입)"),
  flow,
  gap(),
  table(["구분", "프로토타입 기술", "선택 이유"], [
    ["앱", "Kotlin + Jetpack Compose (안드로이드)", "갤럭시탭·폰 전용 앱으로 병원 내부망에서 바로 동작"],
    ["서버", "Python FastAPI", "빠르게 개발하고 기능을 검증하기 좋음"],
    ["데이터베이스", "SQLite", "별도 DB 프로그램 설치·관리가 필요 없고 파일 복사로 백업 가능. 59명 규모에 충분"],
    ["알림", "앱 알림 (직원 기기가 1분마다 내부 서버 확인)", "외부 인터넷·문자 업체 없이 동작. 보호자 휴대폰 푸시(구글 FCM)는 선택"],
    ["보안", "HTTPS(기기마다 서버 등록 QR로 서버 확인), 역할별 권한, 로그인 실패 잠금", "공용 기기와 병원 와이파이에서 비밀번호·환자 정보 보호"],
  ], [1700, 3400, 4538]),
  gap(),
  p("서버는 데이터베이스 접근을 별도 계층(SQLAlchemy)으로 분리해 두었다. 그래서 채택 후 데이터베이스를 바꿔도 업무 기능 코드는 대부분 그대로 쓸 수 있다."),
];

const sec7 = [
  h1("7. 보안 및 개인정보 보호"),
  ...bullets([
    "**내부망 운영: **병원 내부 서버에서만 동작하고 외부 클라우드를 쓰지 않는다. 기본 기능은 서버가 외부 인터넷에 연결하지 않는다",
    "**보호자 조회는 병원 기기에서만: **보호자 휴대폰으로는 조회하지 않으며, 공용 기기이므로 1분 동안 조작이 없거나 [닫기]를 누르면 바로 로그아웃된다",
    "**통신 암호화: **앱과 서버 사이의 모든 통신을 HTTPS로 암호화한다. 병원 기기는 처음 한 번 서버 등록 QR을 찍어 진짜 병원 서버만 믿도록 설정한다",
    "**직원 계정: **본인이 직접 설정한 비밀번호(8자 이상, 영문+숫자), 10분 동안 조작이 없으면 자동 로그아웃, 로그인 5회 실패 시 10분 잠금",
    "**보호자 계정: **자기 아기 정보만 조회 가능, 로그인 5회 실패 시 10분 잠금, 퇴원하면 로그인 불가",
    "**권한 검사: **화면에서 버튼을 숨기는 것과 별개로, 서버가 모든 요청의 권한을 확인한다",
    "**기록 보존: **기록은 지우지 않고 '취소' 기록을 추가해 바로잡으므로 모든 변경 내역이 남는다",
    "**퇴원 환자 개인정보 말소: **퇴원 처리는 관리자만 하며, 퇴원 즉시 보호자 로그인과 알림이 중단된다. 퇴원 30일 뒤(설정 가능) 아기 이름·등록번호·보호자 연락처·메모를 자동으로 지우고, 관리자는 즉시 지울 수도 있다. 팩 수 기록은 익명으로 남겨 통계를 유지한다",
    "**개인정보 담당자 확인: **저장 항목(아기 이름, 등록번호, 보호자 전화번호)과 보관 기간. 보호자 푸시를 쓸 경우 동의 절차 (푸시 문구에는 개인정보를 넣지 않음)",
  ]),
];

const sec8 = [
  h1("8. 추진 단계"),
  p("먼저 쓸 수 있는 기능부터 차례로 만든다. 1~2단계가 끝나면 종이 장부 대신 쓸 수 있고, 알림과 보호자 기능은 그다음에 더한다. 단계별 기간은 병원 측 준비(전산팀 협의) 상황에 맞춰 확정한다."),
  table(["단계", "내용", "완료 기준"], [
    ["0. 병원 측 준비", "전산팀·개인정보 담당자 협의, 신생아실 조회용 태블릿 마련 (개발과 동시에 진행)", "협의 완료"],
    ["1. 서버 기초", "직원 로그인, 환자 등록, 입고·사용·폐기·취소, 잔량 계산", "자동 테스트 통과"],
    ["2. 서버 보강 + 간호사 앱", "2-1 서버 보강(HTTPS와 서버 등록 QR, DB 버전 관리, 로그인 실패 잠금) → 2-2 간호사 앱(환자 목록·검색, 입고·사용·폐기 화면, 확인 창)", "여러 기기 동시 입력 시 잔량 일치 → 실사용 가능"],
    ["3. 관리자 기능", "전체 현황, 일별 사용량·그래프, 퇴원 처리와 개인정보 말소, 계정·설정 관리", "관리자가 현황·통계 확인 가능"],
    ["4. 재고 부족 알림", "부족 판정, 중복 방지, 직원 기기 앱 알림, '보호자 연락 필요' 목록과 연락 완료 기록", "기준 아래로 내려가면 모든 직원 기기에 1분 안에 알림"],
    ["5. 보호자 조회 화면", "병원 기기용 보호자 로그인·조회 화면, 부족 안내, 1분 자동 로그아웃", "다른 아기 정보 접근 불가, 자동 로그아웃 확인"],
    ["6. 시범 운영", "실제 환자 일부로 1~2주 운영 후 의견 반영", "간호사 동의"],
    ["7. 설치·운영", "정식 설치, 매일 자동 백업, 사용 설명서", "59명 등록, 정식 사용 시작"],
    ["선택. 보호자 푸시", "병원이 외부 연결(구글 FCM)을 허용하면 보호자 휴대폰 푸시 알림 추가", "시험 휴대폰으로 푸시 수신"],
  ], [2000, 4600, 3038]),
];

const sec9 = [
  h1("9. 프로토타입 범위와 향후 계획"),
  h2("9.1 프로토타입 범위"),
  p("한 병동, 신생아 환자 약 59명 규모에서 위 1~7단계의 기능을 갖춘 프로토타입을 만들어 실제 업무에 써 보고 효과를 검증한다."),
  h2("9.2 채택 시 안정화·확장 계획"),
  p("프로토타입은 빠른 검증을 위해 단순한 구성을 택했다. 병원에 채택되면 병원 규모와 정책에 맞춰 다음과 같이 기술 구성을 바꿔 안정화한다."),
  table(["항목", "프로토타입", "채택 후 (병원 규모에 따라)"], [
    ["데이터베이스", "SQLite (파일 1개)", "PostgreSQL 등 서버형 데이터베이스로 전환 (여러 병동, 동시 사용자 증가 대비)"],
    ["서버", "내부 서버 1대", "서버 이중화, 장애 감시(모니터링)"],
    ["백업", "매일 DB 파일 백업 (30일 보관)", "실시간 복제와 정기 복구 훈련"],
    ["DB 구조 변경 관리", "마이그레이션 도구(Alembic)로 버전 관리 (실사용 전 도입)", "데이터베이스 전환 시 같은 도구로 이전"],
    ["앱 배포", "APK 파일 직접 설치", "MDM 또는 관리형 Google Play로 배포·업데이트"],
    ["보호자 조회·알림", "병원 기기에서 조회, 직원 앱 알림", "병원 정책에 따라 보호자 휴대폰 푸시·병원 밖 조회(앱 또는 웹) 검토"],
    ["외부 연동", "없음", "전자의무기록(EMR)과 환자 정보 연동"],
    ["관리 범위", "팩 개수", "유축일·유효기간 관리, 바코드/QR 스캔"],
    ["적용 범위", "한 병동", "여러 병동·여러 병원"],
  ], [2000, 2800, 4838]),
];

const sec10 = [
  h1("10. 병원 측 협조 요청 사항"),
  h2("10.1 전산팀"),
  ...bullets([
    "내부 서버 1대를 둘 자리 (24시간 켜 두는 PC 또는 가상 서버)",
    "업무용 갤럭시탭·폰과 신생아실 조회용 태블릿에 앱 파일(APK) 설치 허용 여부 (MDM 사용 여부)",
    "병원 기기가 연결되는 와이파이에서 내부 서버의 앱용 포트 하나에 접속 허용",
    "(보호자 푸시를 쓸 경우에만) 서버에서 구글 푸시 서버(FCM)로 나가는 연결 허용",
  ]),
  h2("10.2 개인정보 담당자"),
  ...bullets([
    "아기 이름, 등록번호, 보호자 전화번호를 내부 서버에 저장하는 것에 대한 병원 정책 확인",
    "퇴원 후 개인정보 보관 기간 (기본 30일이 병원 정책에 맞는지)",
    "(보호자 푸시를 쓸 경우) 보호자 동의 절차",
  ]),
  h2("10.3 신생아실"),
  ...bullets([
    "보호자 조회용 태블릿 1대를 둘 자리와 보호자 안내 방법",
  ]),
];

const sec11 = [
  h1("11. 변경 이력"),
  table(["버전", "날짜", "변경 내용"], CHANGELOG.map(([v, d, c]) => [`v${v}`, d, c]), [1200, 1800, 6638], { firstColBold: false }),
];

// ───────────────────────── 문서 조립 ─────────────────────────
const doc = new Document({
  creator: "",
  title: `${TITLE} ${SUBTITLE}`,
  styles: {
    default: {
      document: { run: { font: F, size: 21 } },
      heading1: {
        run: { font: F, size: 30, bold: true, color: ACCENT },
        paragraph: { spacing: { before: 360, after: 160 }, keepNext: true,
          border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: ACCENT, space: 4 } } },
      },
      heading2: {
        run: { font: F, size: 24, bold: true, color: "2E2E2E" },
        paragraph: { spacing: { before: 240, after: 100 }, keepNext: true },
      },
    },
  },
  numbering: {
    config: [{
      reference: "bullets",
      levels: [
        { level: 0, format: LevelFormat.BULLET, text: "•", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 400, hanging: 260 } } } },
        { level: 1, format: LevelFormat.BULLET, text: "-", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 800, hanging: 260 } } } },
      ],
    }],
  },
  sections: [{
    properties: { page: { margin: { top: 1300, bottom: 1300, left: 1134, right: 1134 } }, titlePage: true },
    headers: {
      default: new Header({ children: [new Paragraph({ alignment: AlignmentType.RIGHT, children: [run(`${TITLE} ${SUBTITLE}  v${VERSION}`, { size: 16, color: "808080" })] })] }),
    },
    footers: {
      default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER, children: [new TextRun({ children: ["- ", PageNumber.CURRENT, " -"], font: F, size: 18, color: "808080" })] })] }),
    },
    children: [...cover, ...sec1, ...sec2, ...sec3, ...sec4, ...sec5, ...sec6, ...sec7, ...sec8, ...sec9, ...sec10, ...sec11],
  }],
});

const out = path.join(__dirname, "모유재고관리_기획서.docx");
Packer.toBuffer(doc).then((buf) => {
  fs.writeFileSync(out, buf);
  console.log("생성:", out);
});
