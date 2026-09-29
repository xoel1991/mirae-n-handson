# 문항 은행 비즈니스 규칙 (코드 기준 추출)

- 대상: `legacy/item-bank-php` 의 `search.php`, `register.php`, `units.php`, `inc/db.php` (외부 라이브러리 `vendor/` 는 읽지 않음)
- `docs/` 는 읽지 않았다. 주석과 코드가 다르면 코드를 기준으로 썼다.
- 문항 **수정** 화면(파일)은 이 폴더에 없다. 수정 시 검증 규칙은 코드에 없으므로 쓰지 않았다.

## A. 검색 조건 조합

### CX-01 키워드는 제목 또는 지문의 부분 일치다
- 규칙: 키워드(`q`)는 앞뒤 공백을 제거한 뒤 `title` 또는 `stem` 중 하나에 포함되면 일치한다.
- 근거: `legacy/item-bank-php/search.php:85`, `search.php:97-98`
```php
$q = trim($q);
$like = '%' . $q . '%';
$where .= " AND (title LIKE ? OR stem LIKE ?)";
```

### CX-02 키워드는 100자까지만 쓰고, `%` `_` 는 이스케이프하지 않는다
- 규칙: 100자를 넘으면 잘라서 검색하고 경고만 남긴다. `%` · `_` 는 와일드카드로 그대로 전달된다.
- 근거: `search.php:86-88`, `search.php:94-97`
```php
if (mb_strlen($q, 'UTF-8') > 100) {
    $q = mb_substr($q, 0, 100, 'UTF-8');
$like = '%' . $q . '%';
```

### CX-03 단원은 코드가 정확히 같을 때만 일치한다 (형식 · 대소문자 검사는 경고뿐)
- 규칙: `unit` 은 `unit_code = ?` 로 비교한다. 형식이 틀리거나 소문자여도 값을 바꾸지 않고 그대로 조회하며 경고만 표시한다.
- 근거: `search.php:109-120`
```php
if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) {
    $warnings[] = '단원 코드 형식이 올바르지 않습니다. (예: M5-1)';
$where .= " AND unit_code = ?";
```

### CX-04 난이도를 비우면 난이도 5 문항은 제외된다 (주석과 다름)
- 규칙: `level` 이 빈값이면 `level < 5` 조건이 붙어 난이도 5 문항이 결과에서 빠진다.
- 근거: `search.php:213-215`. 주석은 "전체 난이도 검색 (1~5 모두 포함)" 이라고 하지만 코드는 5 를 제외한다.
```php
// 난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)
if ($level == '') {
    $where .= " AND level < 5";
```

### CX-05 난이도 1~5 를 주면 그 값과 같은 문항만 나온다
- 규칙: `^[1-5]$` 에 맞는 값은 `level = ?` 로 비교한다. 이 경우 5 도 조회된다.
- 근거: `search.php:217-221`
```php
else if (preg_match('/^[1-5]$/', $level)) {
    $where .= " AND level = ?";
    $values[] = (int)$level;
```

### CX-06 1~5 밖의 난이도는 정수로 바꿔 같은 값만 찾는다
- 규칙: 범위 밖이거나 숫자가 아닌 값은 경고를 남기고 `(int)` 변환값과의 동등 비교를 한다(문자열 `abc` 는 0 이 된다).
- 근거: `search.php:223-229`
```php
$warnings[] = '난이도는 1~5 사이여야 합니다.';
$where .= " AND level = ?";
$values[] = (int)$level;
```

### CX-07 태그는 이름이 정확히 같은 태그가 붙은 문항만 나온다
- 규칙: 태그명은 50자까지만 쓰고, `item_tag` · `tag` 를 EXISTS 로 확인해 `t.name = ?` 인 문항만 남긴다. 등록되지 않은 태그도 그대로 조회한다(결과 0건).
- 근거: `search.php:240-247`, `search.php:258-260`
```php
$where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
        . " WHERE it.item_id = v_item_public.id AND t.name = ?)";
```

### CX-08 조건은 모두 AND 로 결합되고, 조건마다 값은 하나만 쓴다
- 규칙: 키워드 · 단원 · 난이도 · 태그는 모두 AND 다. 같은 파라미터가 배열로 오면 첫 값만 쓴다. 태그를 여러 개 고르는 기능은 없다.
- 근거: `search.php:44`, `search.php:55`, `search.php:98`, `search.php:118`, `search.php:244`
```php
$where   = " WHERE 1=1";
$q = is_array($params['q']) ? (string)reset($params['q']) : (string)$params['q'];
```

## B. 목록 기본 정렬 · 정렬 옵션 · 페이지

### CX-09 기본 정렬은 난이도 내림차순, 같으면 번호 오름차순이다
- 규칙: `sort` 가 비었거나 알 수 없는 값이면 `level DESC, id ASC` 를 쓴다. 알 수 없는 값이면 경고를 표시한다.
- 근거: `search.php:317-325`
```php
case '':
    // 기본 정렬: 어려운 문항부터, 같은 난이도면 번호 순
    $orderBy = " ORDER BY level DESC, id ASC";
```

### CX-10 정렬 기준별 방향 기본값과 보조 정렬이 다르다
- 규칙: `dir` 은 `asc`/`desc` 만 인정하고 그 밖의 값은 비운다. `id` · `title` · `unit` 은 `desc` 일 때만 내림차순(기본 오름차순)이고, `level` · `created` 는 `asc` 일 때만 오름차순(기본 내림차순)이다. `id` 를 뺀 나머지는 `id ASC` 를 보조 키로 붙이고, `unit` 은 `unit_code, level DESC, id ASC` 다.
- 근거: `search.php:268-273`, `search.php:293-299`, `search.php:301-307`
```php
$orderBy = " ORDER BY unit_code ASC, level DESC, id ASC";
$orderBy = " ORDER BY level DESC, id ASC";   // dir 이 asc 가 아닐 때
```

### CX-11 한 페이지 20건, 페이지 번호는 1~999 로 보정한다
- 규칙: 숫자가 아니면 1, 1 미만이면 1, 999 초과면 999 로 바꾸고 `LIMIT 20 OFFSET (page-1)*20` 을 쓴다.
- 근거: `search.php:336-349`, `search.php:523`
```php
if ($page > 999) {
    $page = 999;
$limit  = " LIMIT 20 OFFSET " . (int)$offset;
```

## C. 제외 조건 (공개 문항만 노출)

### CX-12 검색은 `v_item_public` 뷰에서만 조회한다
- 규칙: 검색 결과와 총 건수는 `item` 테이블이 아니라 공개 문항 뷰 `v_item_public` 기준이다. 어떤 문항이 뷰에서 빠지는지(정확한 필터 조건)는 뷰 정의가 이 폴더에 없어 여기서는 확인하지 못했다.
- 근거: `search.php:521-526`
```php
$from   = " FROM v_item_public";
$countSql = "SELECT COUNT(*) AS cnt" . $from . $where;
```

### CX-13 공개 문항은 상태 코드 `A` 로 판단하며, 검수중(`R`)은 세지 않는다
- 규칙: 단원 목록의 "공개 문항 수"는 `status = 'A'` 인 문항만 센다. 주석은 삭제 · 검수중 문항을 세지 않는다고 적는다(다른 상태 값은 코드에 나오지 않는다).
- 근거: `legacy/item-bank-php/units.php:15-17`
```php
// 단원별 공개(status='A') 문항 수. 삭제 · 검수중 문항은 세지 않는다.
(SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count
```

## D. 등록 검증

### CX-14 제목은 앞뒤 공백 제거 후 5자 이상이다
- 규칙: 공백을 제거한 뒤 글자 수(`mb_strlen`)가 5 미만이면 오류다. 내부 공백은 글자 수에 포함된다.
- 근거: `legacy/item-bank-php/register.php:41`, `register.php:49-51`
```php
$title  = isset($_POST['title'])  ? trim((string)$_POST['title'])  : '';
if (mb_strlen($title, 'UTF-8') < 5) {
```

### CX-15 제목은 200자를 넘을 수 없다
- 근거: `register.php:52-54`
```php
if (mb_strlen($title, 'UTF-8') > 200) {
    $errors[] = '제목은 200자를 넘을 수 없습니다.';
```

### CX-16 지문은 필수다
- 규칙: 앞뒤 공백 제거 후 빈 문자열이면 오류다. 길이 상한은 코드에 없다.
- 근거: `register.php:42`, `register.php:56-58`
```php
if ($stem === '') {
    $errors[] = '지문을 입력해야 합니다.';
```

### CX-17 단원은 필수이며 단원 목록에 있는 id 여야 한다
- 규칙: 등록 폼의 `unit_id` 가 `unit` 테이블에서 읽은 id 중 하나와 같아야 한다.
- 근거: `register.php:27`, `register.php:60-68`
```php
if ((string)$u['id'] === $unitId) {
    $unitOk = true;
```

### CX-18 난이도는 1~5 의 한 자리 숫자다
- 근거: `register.php:70-72`
```php
if (!preg_match('/^[1-5]$/', $level)) {
    $errors[] = '난이도는 1~5 사이여야 합니다.';
```

### CX-19 목록에 없는 태그 id 는 오류 없이 버린다
- 규칙: 태그는 선택 사항이다. `tag` 테이블에 없는 id 는 오류를 내지 않고 저장에서 빠진다.
- 근거: `register.php:74-81`
```php
if ((string)$t['id'] === (string)$tid) {
    $validTagIds[] = (int)$tid;
```

### CX-20 오류가 하나라도 있으면 저장하지 않고 오류를 모두 보여 준다
- 규칙: 검증은 첫 오류에서 멈추지 않고 전부 수행해 모은다. `$errors` 가 비었을 때만 저장한다.
- 근거: `register.php:83`
```php
if (empty($errors)) {
```

### CX-21 새 문항은 검수중(`R`)으로 저장되어 검수 완료 전까지 검색에 나오지 않는다
- 규칙: 등록한 문항의 상태는 항상 `R` 이다. 검색은 공개 뷰만 보므로(CX-12) 바로 보이지 않는다.
- 근거: `register.php:84`, `register.php:93-94`
```php
// 등록 직후에는 검수중(R) 상태로 들어간다 → 검수 완료 전까지 검색 화면에 나오지 않는다.
VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())
```
- 주의: "검수 완료 후 `A` 가 된다"는 처리는 이 폴더 코드에 없다. 검색에서 제외되는 이유는 뷰 정의에 달려 있다(CX-12).

### CX-22 문항 번호는 `MAX(id)+1` 로 정하고 문항과 태그는 한 트랜잭션으로 저장한다
- 규칙: 최대 id 에 1 을 더해 새 id 를 만들고, 문항과 태그 저장 중 하나라도 실패하면 롤백한다.
- 근거: `register.php:85-87`, `register.php:120`
```php
$conn->begin_transaction();
$res = $conn->query("SELECT COALESCE(MAX(id), 0) + 1 AS next_id FROM item FOR UPDATE");
```
