# Bài 3: Phân tích lý do bắt buộc của Defensive Validation trong ETL với LLM

## 1. Đặt vấn đề

Trong hệ thống AI Logistics Incident Reporter, LLM được sử dụng để bóc tách tin nhắn thô của tài xế thành dữ liệu có cấu trúc.

Luồng xử lý:

```text
Raw Message
     |
     v
    LLM
     |
     v
JSON / Structured Output
     |
     v
IncidentExtraction DTO
     |
     v
Defensive Validation
     |
     v
IncidentReport Entity
     |
     v
Database
```

Một lỗi thiết kế phổ biến là cho rằng chỉ cần cung cấp JSON Schema hoặc Format Instructions cho LLM thì dữ liệu trả về chắc chắn hợp lệ.

Đây là giả định không an toàn.

LLM có thể tạo ra output không đúng schema, thiếu dữ liệu, sai định dạng hoặc chứa giá trị không phù hợp với nghiệp vụ. Vì vậy, **Defensive Validation bắt buộc phải được thực hiện sau bước parse và trước khi mapping dữ liệu vào Entity/database**.

---

## 2. JSON Schema và Format Instructions giải quyết vấn đề gì?

`BeanOutputConverter` có thể tạo ra format instructions để hướng dẫn LLM trả về cấu trúc mong muốn.

Ví dụ:

```java
public record IncidentExtraction(
        String orderCode,
        String licensePlate,
        String incidentType,
        String urgency
) {
}
```

LLM được yêu cầu trả về:

```json
{
  "orderCode": "ORD-001",
  "licensePlate": "29A-12345",
  "incidentType": "TIRE_DAMAGE",
  "urgency": "HIGH"
}
```

Điều này giúp tăng khả năng LLM tạo ra output đúng cấu trúc.

Tuy nhiên:

> **Format Instructions là cơ chế hướng dẫn model, không phải lớp bảo mật hay business validation.**

---

## 3. LLM không phải nguồn dữ liệu đáng tin cậy

LLM là mô hình sinh dữ liệu dựa trên xác suất.

Ngay cả khi prompt yêu cầu:

```text
Chỉ trả về JSON hợp lệ.
```

model vẫn có thể trả về:

```text
```json
{
  "orderCode": "ORD-001",
  "licensePlate": "29A-12345",
  "incidentType": "TIRE_DAMAGE",
  "urgency": "HIGH"
}
```
```

Hoặc:

```json
{
  "orderCode": null,
  "licensePlate": null,
  "incidentType": "TIRE_DAMAGE",
  "urgency": "HIGH"
}
```

Hoặc:

```json
{
  "orderCode": "",
  "licensePlate": "UNKNOWN",
  "incidentType": "TIRE_DAMAGE",
  "urgency": "VERY_HIGH"
}
```

Có thể parse JSON thành công nhưng dữ liệu vẫn **không hợp lệ về mặt nghiệp vụ**.

Đây chính là lý do cần Defensive Validation.

---

## 4. Parse thành công không có nghĩa là dữ liệu hợp lệ

Có hai khái niệm hoàn toàn khác nhau:

- **Syntactic Validity:** dữ liệu đúng cú pháp JSON và có thể deserialize.
- **Business Validity:** dữ liệu thỏa mãn các quy tắc nghiệp vụ của hệ thống.

Ví dụ:

```json
{
  "orderCode": null,
  "licensePlate": null,
  "incidentType": "TIRE_DAMAGE",
  "urgency": "HIGH"
}
```

JSON hoàn toàn hợp lệ về cú pháp. Jackson có thể deserialize thành `IncidentExtraction` mà không ném lỗi.

Nhưng database có thể yêu cầu:

```sql
order_code NOT NULL
license_plate NOT NULL
```

Do đó:

```text
JSON parse = SUCCESS
Business validation = FAIL
Database save = MUST NOT EXECUTE
```

---

## 5. Defensive Validation là gì?

Defensive Validation là nguyên tắc:

> Không tin tưởng dữ liệu đầu vào, kể cả dữ liệu được tạo bởi hệ thống nội bộ hoặc AI; luôn kiểm tra dữ liệu trước khi đưa vào tầng có trạng thái hoặc side effect.

Trong ETL với LLM:

```text
LLM Output
     |
     v
Parse
     |
     v
DTO
     |
     v
Defensive Validation
     |
     +---------- FAIL ----------+
     |                           |
     v                           v
   VALID                    Reject / Retry
     |
     v
Entity
     |
     v
Database
```

DTO đóng vai trò như một **boundary** giữa dữ liệu không đáng tin cậy và domain/persistence layer.

---

## 6. Vì sao phải validate `orderCode`?

Yêu cầu:

```text
orderCode không được rỗng
```

Các giá trị sau phải bị từ chối:

```text
null
""
"   "
```

Ví dụ:

```java
if (dto.orderCode() == null || dto.orderCode().isBlank()) {
    throw new IllegalArgumentException("orderCode must not be blank");
}
```

Lý do:

- Database có thể khai báo `NOT NULL`.
- Order code có thể là khóa nghiệp vụ.
- Entity không thể đại diện cho Incident nếu thiếu order code.
- Không nên chờ database phát hiện lỗi.

Thay vì:

```text
LLM
 |
 v
Entity
 |
 v
DB
 |
 X
Constraint violation
```

nên:

```text
LLM
 |
 v
DTO
 |
 v
Validation
 |
 X
Business validation error
```

Lỗi được phát hiện càng sớm càng tốt.

---

## 7. Vì sao phải validate `licensePlate`?

Biển số xe không chỉ cần khác `null`. Nó phải tuân theo định dạng nghiệp vụ đã thống nhất.

Ví dụ:

```java
private static final Pattern LICENSE_PLATE_PATTERN =
        Pattern.compile("^[0-9]{2}[A-Z]-[0-9]{4,5}$");
```

Sau đó:

```java
if (dto.licensePlate() == null
        || !LICENSE_PLATE_PATTERN.matcher(dto.licensePlate()).matches()) {
    throw new IllegalArgumentException("Invalid license plate");
}
```

Ví dụ:

```text
29A-12345
```

có thể hợp lệ theo format đang quy ước.

Trong khi:

```text
UNKNOWN
ABC
123
NULL
```

phải bị từ chối.

Điểm quan trọng:

> JSON Schema có thể kiểm tra kiểu dữ liệu hoặc pattern nếu schema được áp dụng thực sự, nhưng business rule thực tế vẫn cần được kiểm tra ở application layer.

---

## 8. Vì sao phải validate `urgency`?

Giả sử hệ thống chỉ cho phép:

```text
LOW
MEDIUM
HIGH
CRITICAL
```

Nhưng LLM trả:

```json
{
  "urgency": "VERY_HIGH"
}
```

JSON vẫn có thể hợp lệ, nhưng `VERY_HIGH` không thuộc domain model.

Có thể kiểm tra:

```java
private static final Set<String> VALID_URGENCY =
        Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
```

Sau đó:

```java
if (!VALID_URGENCY.contains(dto.urgency())) {
    throw new IllegalArgumentException(
            "Invalid urgency: " + dto.urgency()
    );
}
```

Tốt hơn nữa là sử dụng enum:

```java
public enum Urgency {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
```

---

## 9. JSON Schema không thay thế Business Validation

Có thể hình dung thành hai tầng:

```text
              LLM
               |
               v
        Format Instructions
               |
               v
        JSON Structure
               |
               v
          JSON Parsing
               |
               v
        Structural Check
               |
               v
      Defensive Validation
               |
               v
       Business Validation
               |
               v
            Entity
```

### Schema/Format Instructions

Tập trung vào:

- Cấu trúc JSON.
- Tên field.
- Kiểu dữ liệu.
- Format output.
- Hướng dẫn model.

### Defensive Validation

Tập trung vào:

- Dữ liệu có đủ hay không.
- Dữ liệu có hợp lệ theo nghiệp vụ không.
- Giá trị có nằm trong domain cho phép không.
- Dữ liệu có an toàn trước khi persistence không.

Hai lớp này bổ sung cho nhau chứ không thay thế nhau.

---

## 10. Vì sao không thể tin hoàn toàn vào BeanOutputConverter?

`BeanOutputConverter` giúp deserialize output của model thành Java object.

Ví dụ:

```java
IncidentExtraction dto = converter.convert(response);
```

Nếu JSON là:

```json
{
  "orderCode": null,
  "licensePlate": "UNKNOWN",
  "urgency": "INVALID"
}
```

converter vẫn có thể tạo được `IncidentExtraction`.

Bởi vì:

```text
JSON hợp lệ
```

không đồng nghĩa với:

```text
Business data hợp lệ
```

Do đó cần:

```java
IncidentExtraction dto = converter.convert(response);
validate(dto);
```

chứ không nên:

```java
IncidentExtraction dto = converter.convert(response);
repository.save(mapToEntity(dto));
```

---

## 11. Defensive Validation bảo vệ Database

Database là lớp bảo vệ cuối cùng, không nên là lớp validation chính.

Sai lầm:

```text
LLM
 |
 v
DTO
 |
 v
Entity
 |
 v
Database
 |
 X
NOT NULL violation
```

Thiết kế tốt:

```text
LLM
 |
 v
DTO
 |
 v
Validation
 |
 X
Business Exception
```

Nếu dữ liệu hợp lệ:

```text
LLM
 |
 v
DTO
 |
 v
Validation
 |
 v
Entity
 |
 v
Database
```

Application nên phát hiện lỗi càng sớm càng tốt.

---

## 12. Defensive Validation bảo vệ Domain Model

JPA Entity đại diện cho trạng thái mà hệ thống chấp nhận.

Nếu Entity có:

```java
private String orderCode;
private String licensePlate;
private Urgency urgency;
```

thì trước khi tạo Entity phải đảm bảo:

```text
orderCode != null
licensePlate hợp lệ
urgency thuộc domain
```

Không nên để Entity trở thành nơi chứa dữ liệu chưa kiểm chứng.

---

## 13. Defensive Validation và Transaction

Trong ETL service, nên kết hợp validation với:

```java
@Transactional
```

Ví dụ:

```java
@Transactional
public IncidentReport processReport(String rawMessage) {

    // 1. Call LLM
    // 2. Clean response
    // 3. Parse DTO
    // 4. Validate DTO
    // 5. Map Entity
    // 6. Save
}
```

Nếu validation thất bại:

```text
Validation Exception
        |
        v
Transaction rollback
```

Nếu database xảy ra lỗi:

```text
Database Exception
        |
        v
Transaction rollback
```

Điều này đảm bảo quá trình ETL không để lại trạng thái persistence không nhất quán.

---

## 14. Defensive Validation và lỗi Markdown

Một lỗi khác là model trả:

```text
```json
{
   ...
}
```
```

Jackson có thể không parse được trực tiếp.

Do đó cần preprocessing:

```java
private String cleanJsonResponse(String response) {

    if (response == null) {
        throw new IllegalArgumentException("AI response must not be null");
    }

    return response
            .replaceFirst("^```json\s*", "")
            .replaceFirst("^```\s*", "")
            .replaceFirst("\s*```$", "")
            .trim();
}
```

Luồng:

```text
AI Response
     |
     v
Clean Markdown
     |
     v
JSON
     |
     v
BeanOutputConverter
     |
     v
DTO
     |
     v
Defensive Validation
```

Việc làm sạch Markdown giải quyết vấn đề **format**, còn Defensive Validation giải quyết vấn đề **data correctness**.

Đây là hai vấn đề khác nhau.

---

## 15. Vì sao validation phải nằm trước mapping Entity?

Không nên:

```java
IncidentReport entity = new IncidentReport();

entity.setOrderCode(dto.orderCode());
entity.setLicensePlate(dto.licensePlate());
entity.setUrgency(dto.urgency());

validate(entity);

repository.save(entity);
```

Tốt hơn:

```java
validate(dto);

IncidentReport entity = new IncidentReport();

entity.setOrderCode(dto.orderCode());
entity.setLicensePlate(dto.licensePlate());
entity.setUrgency(dto.urgency());

repository.save(entity);
```

Lý do là Entity chỉ nên được tạo sau khi dữ liệu đã vượt qua boundary validation.

```text
Untrusted DTO
      |
      v
Validation Boundary
      |
      v
Trusted Domain Data
      |
      v
Entity
```

---

## 16. Các tầng bảo vệ trong kiến trúc

Một hệ thống ETL AI tốt nên có nhiều lớp bảo vệ:

```text
                 Raw Message
                      |
                      v
                    LLM
                      |
                      v
              Output Formatting
                      |
                      v
               JSON Cleaning
                      |
                      v
                JSON Parsing
                      |
                      v
             Structural Validation
                      |
                      v
             Defensive Validation
                      |
                      v
             Business Validation
                      |
                      v
                 Mapping
                      |
                      v
                JPA Entity
                      |
                      v
              Database Constraint
```

Mỗi tầng có một nhiệm vụ khác nhau.

Không nên kỳ vọng một tầng duy nhất bảo vệ toàn bộ hệ thống.

---

## 17. Defense in Depth

Đây là nguyên tắc **Defense in Depth**.

Có nhiều lớp bảo vệ:

```text
Layer 1:
Prompt / Format Instructions

Layer 2:
JSON Cleaning

Layer 3:
JSON Parsing

Layer 4:
DTO Validation

Layer 5:
Business Validation

Layer 6:
JPA Validation

Layer 7:
Database Constraints
```

Nếu một lớp bị bypass hoặc thất bại, lớp tiếp theo vẫn có khả năng ngăn dữ liệu xấu.

Ví dụ:

```text
LLM trả sai
    |
    v
Format instruction không ngăn được
    |
    v
DTO nhận dữ liệu
    |
    v
Defensive Validation phát hiện
    |
    X
Không save DB
```

Đây là thiết kế an toàn hơn nhiều so với việc tin rằng LLM luôn trả đúng.

---

## 18. So sánh trước và sau Refactor

### Trước Refactor

```text
Raw Message
     |
     v
LLM
     |
     v
BeanOutputConverter
     |
     v
IncidentExtraction
     |
     v
IncidentReport
     |
     v
Database
```

Các điểm yếu:

- Không clean Markdown.
- Không validate dữ liệu.
- Có thể nhận `null`.
- Có thể nhận enum không hợp lệ.
- Có thể phát sinh DB constraint violation.
- Logging hạn chế.
- Khó truy vết lỗi.
- Chưa có transaction boundary rõ ràng.

### Sau Refactor

```text
Raw Message
     |
     v
LLM
     |
     v
Clean Response
     |
     v
BeanOutputConverter
     |
     v
IncidentExtraction
     |
     v
Defensive Validation
     |
     +---- Invalid ----> Exception / Rollback
     |
     v
Mapping
     |
     v
IncidentReport
     |
     v
@Transactional
     |
     v
Database
```

Các lợi ích:

- Xử lý output Markdown.
- DTO làm boundary.
- Kiểm tra dữ liệu trước DB.
- Bảo vệ NOT NULL constraints.
- Kiểm tra enum.
- Kiểm tra format biển số.
- Transaction rollback khi lỗi.
- Logging đầy đủ.
- Dễ debug và bảo trì.

---

## 19. Logging cũng là một phần của Defensive Programming

Không chỉ validate, hệ thống cần ghi log có context.

Ví dụ khi nhận message:

```text
INFO  IncidentETLService
Received incident message
```

Sau khi parse thành công:

```text
INFO  IncidentETLService
AI extraction successful: orderCode=ORD-001, licensePlate=29A-12345
```

Khi validation thất bại:

```text
WARN  IncidentETLService
Validation failed: orderCode is blank
```

Khi xảy ra exception:

```text
ERROR IncidentETLService
ETL processing failed: orderCode=ORD-001
```

Logging giúp xác định:

```text
Raw input
    |
AI response
    |
Parsing
    |
Validation
    |
Mapping
    |
Database
```

đã thất bại ở bước nào.

---

## 20. Defensive Validation không phải là không tin tưởng hệ thống

Mục tiêu của Defensive Programming không phải giả định mọi thành phần đều lỗi.

Mục tiêu là:

> Thiết kế hệ thống sao cho khi một thành phần tạo ra dữ liệu bất thường, lỗi không lan truyền sang các tầng khác.

Đặc biệt với AI, đây là nguyên tắc quan trọng vì output mang tính xác suất.

---

## 21. Kết luận

Defensive Validation là bắt buộc trong ETL sử dụng LLM vì:

1. **LLM không đảm bảo tuyệt đối output đúng nghiệp vụ.**
2. **Format Instructions chỉ hướng dẫn model, không phải cơ chế bảo vệ dữ liệu tuyệt đối.**
3. **JSON hợp lệ không đồng nghĩa với business data hợp lệ.**
4. **BeanOutputConverter chỉ thực hiện conversion, không thay thế business validation.**
5. **Database constraint không nên được dùng như lớp validation chính.**
6. **DTO là boundary phù hợp để kiểm tra dữ liệu trước khi tạo Entity.**
7. **Validation sớm giúp lỗi dễ hiểu và dễ xử lý hơn.**
8. **Kết hợp validation với `@Transactional` giúp đảm bảo rollback khi ETL thất bại.**
9. **Nhiều lớp bảo vệ tạo thành Defense in Depth.**
10. **Thiết kế này giúp hệ thống AI ổn định hơn khi triển khai production.**

Kiến trúc cuối cùng:

```text
             LLM
              |
              v
       Clean Response
              |
              v
          Parse DTO
              |
              v
    Defensive Validation
              |
       +------+------+
       |             |
     Invalid        Valid
       |             |
       v             v
    Reject        Mapping
                     |
                     v
              JPA Entity
                     |
                     v
                Repository
                     |
                     v
                  Database
```

### Nguyên tắc cốt lõi

> **Không bao giờ coi dữ liệu do LLM sinh ra là dữ liệu đã được kiểm chứng. Hãy xem LLM output là `untrusted input`, parse thành DTO, thực hiện Defensive Validation, sau đó mới cho phép dữ liệu đi vào Domain và Persistence Layer.**
