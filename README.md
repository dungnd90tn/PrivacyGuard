# PrivacyGuard

PrivacyGuard là ứng dụng Android giúp lọc DNS, quản lý quyền cho phép/chặn tên miền, quan sát truy vấn và giảm dấu vết khi chia sẻ link. Dự án hiện ở giai đoạn MVP, viết bằng Kotlin với Android SDK trực tiếp, hỗ trợ Android 10 trở lên.

Ứng dụng có giao diện tiếng Việt, chế độ sáng/tối và lưu cấu hình, thống kê trên thiết bị. Không cần tài khoản hoặc backend; truy vấn được phép được gửi tới máy chủ DNS người dùng chọn.

## Tính năng

| Chức năng | Khả năng hiện có |
| --- | --- |
| Lọc DNS | VPN cục bộ xử lý DNS hệ thống qua UDP/TCP 53 trên IPv4/IPv6, áp dụng luật trước khi chuyển tiếp. |
| Máy chủ DNS | Chọn Quad9, Cloudflare, Google, AdGuard hoặc thêm cấu hình riêng; hỗ trợ DNS-over-TLS qua cổng 853. |
| Luật | Cho phép/chặn tên miền exact hoặc wildcard, cấu hình nhóm toàn cục/theo ứng dụng và thử quyết định mà không gửi truy vấn mạng. |
| Quan sát | Tổng quan, tên miền theo ứng dụng, danh sách truy vấn bị chặn/gặp lỗi, tìm kiếm, bộ lọc và xuất JSON. |
| Link sạch | Bỏ tham số theo dõi, thêm tham số tùy chỉnh, sao chép/chia sẻ và nhận link qua Android Share. |
| Phân tích URL | Hiển thị nhận định và lý do từ tên miền, đường dẫn, query param; người dùng tự quyết định cách xử lý. |
| Phiên riêng tư | WebView HTTPS trong tiến trình và kho dữ liệu riêng, lọc tracker bên thứ ba, xóa dữ liệu khi kết thúc phiên. |

Ứng dụng dùng adaptive icon từ logo dự án, có lớp monochrome cho launcher Android 13 trở lên.

## Build và chạy Android

Cần JDK 17 và Android SDK gồm `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`. Đặt `ANDROID_HOME` tới SDK hoặc tạo `local.properties` với `sdk.dir=/đường/dẫn/SDK`. Repo có Gradle Wrapper 8.11.1 và kiểm tra SHA-256 của bản phân phối.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK nằm ở `app/build/outputs/apk/debug/app-debug.apk`.

Mở PrivacyGuard, chọn **Bật lọc DNS** và chấp nhận hộp thoại VPN của Android. Chỉ một VPN được hoạt động tại một thời điểm. Dừng bằng nút trong ứng dụng hoặc thông báo dịch vụ. Nếu tiến trình bị dừng, ứng dụng không tự bật lại VPN.

## Chọn máy chủ DNS

Vào **Cài đặt → Máy chủ DNS**, hoặc mục DNS trên Tổng quan. Mặc định là Quad9 với IP chính `9.9.9.9` và dự phòng `149.112.112.112`. Có thể chọn nhà cung cấp khác hoặc nhấn **+** để thêm tên, IP chính, IP dự phòng, cổng DNS thường và tên xác thực TLS. Hỗ trợ sửa/xóa, tối đa 20 cấu hình.

Luồng xử lý:

```text
DNS hệ thống → PrivacyGuard qua UDP/TCP 53 → luật tên miền → máy chủ DNS đã chọn
```

- Khi tắt **Mã hóa DNS**, truy vấn được gửi qua UDP/TCP tới cổng DNS thường của cấu hình, mặc định 53. Phản hồi UDP bị cắt được thử lại qua TCP ở cùng IP/cổng.
- Khi bật **Mã hóa DNS**, truy vấn được gửi qua TLS 853, có xác thực chứng chỉ và tên máy chủ. Nếu thất bại, chỉ thử địa chỉ dự phòng của cùng cấu hình qua TLS; không tự hạ xuống DNS thường.
- DNS tùy chỉnh nhận địa chỉ IP để kết nối. Khi dùng TLS, nhập **Tên xác thực TLS** do nhà cung cấp công bố. Cổng TLS cố định 853, độc lập với cổng DNS thường; ô địa chỉ không nhận URL DoH/DoT.

Lựa chọn được lưu và áp dụng cho truy vấn mới khi VPN đang bật. Truy vấn bị PrivacyGuard chặn nhận NXDOMAIN; lỗi chuyển tiếp nhận SERVFAIL.

## Xem tên miền và truy vấn

1. Mở **Ứng dụng → Bật nhật ký tên miền**. Nhật ký mặc định tắt và chỉ ghi truy vấn mới từ lúc bật.
2. Bật lọc DNS, sau đó sử dụng ứng dụng cần quan sát.
3. Mở **Ứng dụng → tên app** để xem tên miền, số lần chặn/chuyển tiếp/lỗi và nhật ký. Chạm tên miền để xem lý do hoặc tạo luật.
4. Chạm số **Đã chặn** hoặc **Gặp lỗi** trên Tổng quan, hoặc mục tương ứng trong tab **Ứng dụng**, để xem danh sách riêng.

Danh sách hỗ trợ tìm kiếm, lọc theo ngày/ứng dụng/trạng thái, phân trang 30 dòng và xuất đúng kết quả đang lọc. Chi tiết truy vấn giải thích bằng ngôn ngữ thông thường, gợi ý cách xử lý và có nút cho phép, xem luật hoặc đổi DNS theo trường hợp. **Chi tiết kỹ thuật** hiển thị thông tin chẩn đoán kết nối, TLS và kiểm tra phản hồi khi có dữ liệu.

Khi Android không cung cấp danh tính ứng dụng đáng tin cậy, truy vấn nằm trong **Chưa xác định ứng dụng** hoặc **Tất cả tên miền** và áp dụng luật toàn cục. PrivacyGuard không suy đoán ứng dụng từ tên miền.

## Link sạch và phân tích URL

**Link → Làm sạch link** loại bỏ `utm_*`, `fbclid`, `gclid` và các tham số tùy chỉnh. Công cụ giữ thứ tự, encoding, tham số lặp, fragment và các tham số không liên quan. Tên tùy chỉnh phân cách bằng dấu phẩy; dấu `*` cuối tên dùng để khớp tiền tố.

**Phân tích URL** hiển thị nhận định và giải thích từng dấu hiệu. Tham số đo lường như `utm_*` hoặc `gclid` không đủ để kết luận một yêu cầu tải quảng cáo; nhận định không tự tạo luật hay chặn yêu cầu. DNS chỉ thấy tên miền, không thấy đường dẫn hoặc query param của HTTPS.

Trong **Phiên riêng tư → Yêu cầu trong phiên → Bật xem yêu cầu**, có thể xem URL do WebView cung cấp. Danh sách giữ tối đa 100 yêu cầu gần nhất trong bộ nhớ, mặc định ẩn đường dẫn và giá trị tham số. Tắt xem yêu cầu, tạo phiên mới hoặc kết thúc phiên sẽ xóa danh sách. URL của các ứng dụng khác không được phân tích.

## Kiến trúc và mã nguồn

| Thành phần | Vị trí |
| --- | --- |
| Giao diện native và các luồng sử dụng | [MainActivity.kt](app/src/main/java/com/privacyguard/android/MainActivity.kt), [Ui.kt](app/src/main/java/com/privacyguard/android/Ui.kt) |
| VPN, xử lý gói tin, DNS và luật | [DnsVpnService.kt](app/src/main/java/com/privacyguard/android/DnsVpnService.kt), [core/](app/src/main/java/com/privacyguard/android/core/) |
| Cấu hình, thống kê và nhật ký cục bộ | [GuardStore.kt](app/src/main/java/com/privacyguard/android/GuardStore.kt) |
| Phiên trình duyệt riêng tư | [PrivateBrowserActivity.kt](app/src/main/java/com/privacyguard/android/PrivateBrowserActivity.kt) |
| Unit test và kiểm thử thiết bị | [app/src/test/](app/src/test/), [app/src/androidTest/](app/src/androidTest/) |
| Prototype web độc lập | [prototype-web/](prototype-web/) |

[ARCHITECTURE.md](ARCHITECTURE.md) mô tả các module, yêu cầu về quyền riêng tư và thứ tự ưu tiên luật: ngoại lệ tên miền theo ứng dụng → ngoại lệ toàn cục → nhóm theo ứng dụng → nhóm toàn cục → mặc định cho phép. Trong cùng phạm vi, exact ưu tiên hơn wildcard, sau đó chọn hậu tố khớp dài nhất. `*.example.com` chỉ khớp tên miền con; muốn khớp cả tên miền gốc cần thêm `example.com` riêng.

## Dữ liệu và phạm vi bảo vệ

Bộ đếm tổng hợp lưu trong 7 ngày lịch. Nhật ký chi tiết cần người dùng bật, giới hạn 2.000 sự kiện/7 ngày; bộ đếm có thể lớn hơn phần nhật ký còn giữ. Bộ đếm cũ không khôi phục được chi tiết truy vấn. Có xuất JSON qua trình chọn tệp và xóa dữ liệu trong ứng dụng.

Nhật ký DNS không lưu payload gói tin, URL đầy đủ, headers, cookie hoặc body. Thông tin chẩn đoán chỉ lưu metadata khi bật nhật ký. Link và URL phân tích được xử lý trong bộ nhớ; danh sách yêu cầu của phiên riêng tư không ghi vào SQLite, SharedPreferences hoặc JSON xuất.

VPN chỉ định tuyến hai địa chỉ DNS nội bộ. Các app tự dùng DoH/DoT, DNS riêng, địa chỉ IP trực tiếp hoặc kết nối đã cache có thể bỏ qua lọc. IPv6 extension headers và IP fragments chưa được hỗ trợ trong đường DNS. Danh sách tracker khởi đầu nhỏ, chưa có feed đầy đủ.

Android có thể dùng resolver chung hoặc UID dùng chung bởi nhiều package; luật theo ứng dụng chỉ áp dụng khi xác định được một package. Máy chủ DNS có bộ lọc riêng cũng có thể từ chối tên miền PrivacyGuard đã cho phép. **Đã chặn** chỉ tính chặn do PrivacyGuard; **Đã gửi** không bảo đảm ứng dụng đã kết nối thành công.

Phiên riêng tư tắt cookie bên thứ ba, lọc tracker bên thứ ba theo luật, tắt mạng của service worker và chặn ảnh chụp màn hình. Cookie, cache và WebStorage được xóa khi kết thúc và trước phiên mới. Chế độ này không tạo ẩn danh với website/nhà mạng; chặn theo tên miền không phân biệt đường dẫn trên domain dùng chung, và callback WebView không quan sát đầy đủ mọi redirect.

## Kiểm thử

Unit test kiểm tra luật, URL, DNS, checksum, gói tin lỗi, TCP và thống kê. Robolectric chạy view Android và SQLite native trên SDK 35, kiểm tra cấu hình, nhật ký, phạm vi luật, cỡ chữ và render ảnh vào `app/build/ui-previews/`.

Kiểm thử socket JVM dùng UDP/TCP/TLS thật qua loopback để kiểm tra framing, xác thực TLS, phân loại lỗi và thứ tự bảo vệ socket. Chứng chỉ trong `app/src/test/resources/dns-test.p12` là fixture chỉ cho test, không đóng gói vào APK. Kiểm thử WebView dùng callback giả lập và shadow ServiceWorkerController; chúng không chạy Chromium/network thật và không thay thế kiểm thử trên thiết bị.

APK kiểm thử thiết bị kiểm tra lưu trữ, retention, UI và truy vấn qua TUN. Chọn DNS có thể truy cập trước khi chạy. Các lệnh sau dành cho **emulator thử nghiệm Android 13 trở lên**; kiểm thử xóa dữ liệu quan sát của ứng dụng trên emulator:

```sh
./gradlew :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell pm grant com.privacyguard.android android.permission.POST_NOTIFICATIONS
adb -s emulator-5554 shell appops set com.privacyguard.android ACTIVATE_VPN allow
adb -s emulator-5554 shell am instrument -w com.privacyguard.android.test/com.privacyguard.android.MvpInstrumentation
```

Trên thiết bị thật, cấp quyền VPN qua giao diện. Cần kiểm tra thêm vòng đời VPN, chuyển Wi-Fi/di động, khả năng kết nối TLS 853 và xóa dữ liệu phiên trên các thiết bị/WebView mục tiêu. Phạm vi kiểm chứng của từng đợt nằm trong các biên bản ở `docs/`.

## Web prototype

[prototype-web/](prototype-web/README.md) là simulator để thử giao diện và logic luật, dùng Node.js 20 trở lên:

```sh
cd prototype-web
npm start
npm test
```

Mở `http://localhost:3000`. Prototype không lọc lưu lượng thật; dữ liệu demo của web không xuất hiện trong bộ đếm Android.

## Tài liệu

- [Kiến trúc và nguyên tắc](ARCHITECTURE.md).
- [Phạm vi và kiểm chứng MVP](docs/mvp.html).
- [Giao diện mobile và tên miền theo ứng dụng](docs/mobile-redesign.html).
- [Máy chủ DNS và danh sách truy vấn](docs/dns-requests.html).
- [DNS-over-TLS và nhận định URL](docs/query-dns-tls.html).
- [Chẩn đoán lỗi DNS và kiểm tra bố cục UI](docs/dns-error-ui.html).

Các biên bản HTML chứa ảnh kiểm thử, chi tiết triển khai và kết quả kiểm chứng, có thể mở trực tiếp hoặc in A4.
