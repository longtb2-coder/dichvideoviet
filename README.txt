VIDEO VIỆT HÓA — Android, ưu tiên ngoại tuyến

Tính năng trong nhánh phát triển
- Chọn video trong máy hoặc tải từ link HTTP/HTTPS trực tiếp tới tệp video.
- Nhận dạng lời thoại bằng SenseVoice + Silero VAD.
- Quét chữ trong khung hình bằng OCR (Latin, Trung, Nhật, Hàn).
- Dịch lời thoại và chữ sang tiếng Việt bằng ML Kit trên thiết bị.
- Tạo giọng lồng tiếng Việt bằng Piper/sherpa-onnx và căn theo thời gian.
- Xuất MP4 có phụ đề tiếng Việt đốt vào hình và lưu SRT riêng.
- Mô hình tải một lần, được lưu trong bộ nhớ ứng dụng và tái sử dụng ngoại tuyến.

Giới hạn quan trọng
- SenseVoice trong cấu hình này nhận dạng tiếng Trung, tiếng Anh, tiếng Nhật, tiếng Hàn và tiếng Quảng Đông; chưa hỗ trợ mọi ngôn ngữ.
- OCR quét theo khoảng thời gian; chữ xuất hiện nhanh, cách điệu hoặc bị che có thể bị bỏ sót.
- Ứng dụng dịch chữ thành phụ đề, không tự xóa chữ gốc khỏi hình. Xóa chữ gốc tự nhiên cần xử lý inpainting riêng.
- Link mạng xã hội yêu cầu đăng nhập, DRM, link HTML hoặc link phát trực tuyến không phải tệp trực tiếp có thể không tải được.
- Video được mã hóa lại để đốt phụ đề. Tốc độ phụ thuộc độ dài, độ phân giải và chip; hãy thử video ngắn trước.
- Build thành công không thay thế kiểm thử trên điện thoại thật. Cần kiểm tra tải mô hình, nhận dạng, dịch ngoại tuyến sau khi tắt mạng, lồng tiếng, đồng bộ, OCR và phát lại MP4.

Cách dùng
1. Mở app và chọn video hoặc nhập link trực tiếp tới tệp MP4/MOV.
2. Có thể chọn file SRT nguồn; nếu không chọn, app tự nhận dạng lời thoại.
3. Chọn khoảng quét OCR (mặc định 1,5 giây).
4. Nhấn “Bắt đầu dịch”.
5. Lần đầu cần mạng để tải mô hình nhận dạng, VAD, giọng đọc và các mô hình dịch theo ngôn ngữ được phát hiện. Sau đó mô hình đã tải được dùng lại ngoại tuyến.
6. Kết quả nằm trong thư mục dữ liệu ngoài của ứng dụng: Android/data/vn.dichvideo/files/Movies.

Công nghệ
- sherpa-onnx SenseVoice, Silero VAD và Piper TTS.
- ML Kit language identification, translation và OCR.
- FFmpegKit fork tương thích Android 16 KB page size.

Phát hành
Bản này là bản phát triển. Chỉ phát hành APK cho người dùng sau khi CI build và kiểm thử đầu-cuối trên thiết bị thật. Không được hiểu “biên dịch thành công” là “mọi chức năng đã được xác nhận chạy”.


MỞ VIDEO SAU KHI XỬ LÝ
Sau khi xử lý xong, bấm nút “Mở video vừa tạo” ngay trong ứng dụng để mở MP4 đầu ra. Trên Android 10 trở lên, video được xuất vào Bộ nhớ trong/Movies/VideoVietHoa; phụ đề SRT nằm trong Download/VideoVietHoa. Cần cài APK từ bản build mới nhất để có nút mở video trực tiếp.
