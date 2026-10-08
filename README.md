# PrivacyGuard Android MVP

Ứng dụng Android Kotlin (Android 10 trở lên) để lọc DNS cục bộ, quản lý luật và làm sạch link. Giao diện bằng tiếng Việt, dùng Android SDK trực tiếp, không cần tài khoản hoặc backend.

## Chạy và kiểm tra

Cần JDK 17 và Android SDK gồm `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`. Đặt `ANDROID_HOME` tới SDK hoặc tạo `local.properties` với `sdk.dir=/đường/dẫn/SDK`. Gradle Wrapper 8.11.1 đã có trong repo và kiểm tra SHA-256 của bản phân phối.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Mở PrivacyGuard, chọn **Bật lọc DNS** và chấp nhận hộp thoại VPN của Android. Chỉ một VPN được hoạt động tại một thời điểm. Dừng bằng nút trong ứng dụng hoặc thông báo dịch vụ. Nếu tiến trình bị dừng, ứng dụng không tự bật lại VPN.

## Chọn DNS và xem yêu cầu bị chặn/gặp lỗi (0.3.0)

- **Cài đặt → Máy chủ DNS** (hoặc mục DNS trên Tổng quan): chọn Quad9, Cloudflare, Google hoặc AdGuard. Nhấn **+** ở góc trên để thêm DNS tùy chỉnh với tên, IP chính, IP dự phòng tùy chọn và cổng (mặc định 53). Hỗ trợ IP IPv4/IPv6; chưa hỗ trợ hostname hoặc URL DoH/DoT. Có sửa/xóa máy chủ đã thêm, tối đa 20 cấu hình.
- Lựa chọn được lưu trên thiết bị và áp dụng cho các truy vấn tiếp theo, không cần khởi động lại VPN. Chỉ dùng địa chỉ chính/dự phòng của cấu hình đã chọn. Mặc định là Quad9 `9.9.9.9` / `149.112.112.112`; bản này không còn tự chuyển sang nhà cung cấp Cloudflare khi Quad9 gặp lỗi.
- Chạm số **Đã chặn** hoặc **Gặp lỗi** trên Tổng quan, hoặc hai mục tương ứng trong tab **Ứng dụng**. Danh sách có tìm kiếm, lọc theo ngày/app/trạng thái, phân trang và xuất kết quả. Cần bật nhật ký để có tên miền và truy vấn mới; bộ đếm cũ không khôi phục được chi tiết.
- Chi tiết giải thích vì sao bị chặn, hoặc máy chủ chưa trả lời/đang gặp sự cố/từ chối yêu cầu. Có hướng xử lý và nút **Cho phép**, **Xem luật** hoặc **Đổi máy chủ DNS** phù hợp. Mã lỗi và tên package nằm trong **Chi tiết kỹ thuật**.

[Xem giao diện native và biên bản 0.3](docs/dns-requests.html). Ảnh dùng fixture kiểm thử; APK không có dữ liệu mẫu. Máy chủ DNS có bộ lọc riêng có thể từ chối tên miền mà PrivacyGuard đã cho phép; “Đã chặn” chỉ tính chặn do PrivacyGuard, “Đã gửi” không bảo đảm app đã kết nối thành công.

## Xem tên miền theo ứng dụng

Bản **0.2.0** có giao diện sáng/tối, thanh tab dưới và màn hình chi tiết ứng dụng:

1. Mở **Ứng dụng → Bật nhật ký tên miền**. Nhật ký mặc định tắt và chỉ ghi các truy vấn mới từ lúc bật.
2. Chọn **Bật lọc DNS** và cấp quyền VPN; sử dụng ứng dụng cần quan sát.
3. Mở **Ứng dụng → tên app** để xem tên miền, số lần chặn/chuyển tiếp/lỗi và nhật ký. Chạm tên miền để xem lý do hoặc tạo luật.
4. Nếu Android dùng resolver chung, xem **Chưa xác định ứng dụng** hoặc **Tất cả tên miền**. Không suy đoán app từ tên miền; luật tại các màn hình này là toàn cục, có xác nhận phạm vi trước khi lưu.

Tìm kiếm và bộ lọc chạy trên toàn bộ tối đa 2.000 sự kiện, trước khi phân trang 30 dòng. Nút xuất trong màn hình tên miền xuất đúng kết quả đang lọc. Bộ đếm tổng hợp có thể lớn hơn nhật ký đã giới hạn. [Ảnh giao diện native và biên bản 0.2](docs/mobile-redesign.html) dùng dữ liệu kiểm thử, không có dữ liệu mẫu trong APK.

## Tính năng

- VPN chỉ định tuyến hai địa chỉ DNS nội bộ, xử lý UDP/TCP trên IPv4/IPv6. Truy vấn bị chặn nhận NXDOMAIN; truy vấn được phép được gửi tới máy chủ DNS đã chọn, với địa chỉ dự phòng cùng cấu hình; UDP bị cắt được gửi lại qua TCP ở cùng IP/cổng. Lỗi mạng nhận SERVFAIL.
- Luật nhóm toàn cục/theo ứng dụng, ngoại lệ exact/wildcard, thay thế luật trùng và công cụ thử quyết định không truy cập mạng. Danh sách ứng dụng lấy từ các launcher app mà Android cho phép nhìn thấy.
- Link cleaner giữ thứ tự/encoding, tham số lặp và fragment; có tham số tùy chỉnh, sao chép/chia sẻ và nhận link qua Android Share.
- Dashboard từ phản hồi DNS đã ghi vào TUN; tách chặn, chuyển tiếp và lỗi. Lưu bộ đếm tổng hợp trong 7 ngày lịch; nhật ký chi tiết mặc định tắt, nếu bật thì giới hạn 2.000 sự kiện/7 ngày. Có tìm kiếm, bộ lọc, xuất JSON qua trình chọn tệp và xóa dữ liệu.
- WebView HTTPS trong tiến trình/kho dữ liệu riêng; tắt cookie bên thứ ba, chặn tracker bên thứ ba theo luật, tắt truy cập mạng của service worker, xóa cookie/cache/WebStorage khi đóng và trước phiên mới. Không lưu URL vào dashboard; chặn ảnh chụp màn hình của phiên.
- Icon từ ảnh logo đã cung cấp: adaptive icon với nền đen, khoảng an toàn chống cắt và lớp monochrome cho launcher Android 13 trở lên. PNG foreground ở `app/src/main/res/drawable-nodpi/ic_launcher_art.png`.

## Phạm vi và giới hạn

Đây là **bộ lọc DNS**, không phải proxy toàn bộ lưu lượng, VPN mã hóa hoặc bộ chặn mọi tracker. DNS over HTTPS/TLS, DNS tự chọn, địa chỉ IP trực tiếp và kết nối đã cache có thể bỏ qua lọc. IPv6 extension headers và IP fragments chưa được hỗ trợ trong đường DNS. Danh sách tracker khởi đầu nhỏ, không phải feed đầy đủ.

Android thường dùng resolver chung, nên UID của socket DNS không đảm bảo nhận diện được ứng dụng gốc. Khi UID là hệ thống, không hợp lệ hoặc dùng chung bởi nhiều package, sự kiện hiển thị **Chưa xác định ứng dụng** và dùng luật toàn cục. Luật ứng dụng chỉ được áp dụng khi có một package xác định; không suy đoán từ tên miền.

Trình duyệt không tạo ẩn danh với website/nhà mạng; chặn theo tên miền không phân biệt đường dẫn trên domain dùng chung. `shouldInterceptRequest` không kiểm tra lại tất cả redirect. Xóa dữ liệu và vòng đời VPN cần kiểm thử trên các thiết bị/WebView mục tiêu; xóa lúc khởi động bảo vệ phiên kế tiếp khi tiến trình trước bị hệ thống kết thúc đột ngột.

## Kiểm thử trên emulator

Có bộ unit test cho luật, URL, DNS, checksum, gói tin lỗi, TCP và nhóm/lọc tên miền. Các kiểm thử Robolectric 4.17 chạy view Android thật và SQLite native trên SDK 35; có kiểm tra phạm vi luật, opt-in, lưu/chỉnh sửa DNS, danh sách chặn/lỗi, cỡ chữ và render PNG vào `app/build/ui-previews/`. `android.useAndroidX=true` phục vụ thư viện kiểm thử; APK không thêm AndroidX runtime. Các kiểm thử socket JVM chạy DNS UDP/TCP thật qua loopback ở cổng tùy chỉnh và xác nhận gọi bảo vệ socket trước khi gửi. APK kiểm thử thiết bị kiểm tra SQLite/SharedPreferences, retention, các nút UI và truy vấn thực qua TUN; trước khi chạy, chọn một DNS có thể truy cập trên emulator. Chỉ dùng các lệnh sau với **emulator thử nghiệm Android 13 trở lên**, vì kiểm thử xóa dữ liệu quan sát của ứng dụng trên emulator:

```sh
./gradlew :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell pm grant com.privacyguard.android android.permission.POST_NOTIFICATIONS
adb -s emulator-5554 shell appops set com.privacyguard.android ACTIVATE_VPN allow
adb -s emulator-5554 shell am instrument -w com.privacyguard.android.test/com.privacyguard.android.MvpInstrumentation
```

Trên thiết bị thật, cấp quyền VPN qua giao diện. Kiểm tra thêm: từ chối quyền; bật/dừng liên tiếp; thu hồi VPN; chuyển Wi-Fi/di động; DNS mã hóa; trang HTTPS có cookie/localStorage và phiên mới sau khi đóng/kill tiến trình. Xem [phạm vi và biên bản MVP](docs/mvp.html) và [kiến trúc](ARCHITECTURE.md).

## Web prototype

`prototype-web/` vẫn là simulator độc lập, không lọc lưu lượng thật:

```sh
cd prototype-web
npm start
npm test
```

Mở `http://localhost:3000`. Dữ liệu demo của web không xuất hiện trong bộ đếm Android.
