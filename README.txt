DICH VIDEO VIET - ban OFFLINE (khong dung API), build bang dien thoai

CONG NGHE: sherpa-onnx (SenseVoice nhan dang zh/en/ja/ko/yue + Silero VAD cat cau + Piper doc tieng Viet),
ML Kit (dich + nhan dien ngon ngu tren may), FFmpeg (ghep).

A. BUILD APK (GitHub Actions, lam tren dien thoai)
1) Tao repository Private tren github.com.
2) Dua code len repo:
   - Termux: pkg install git unzip; unzip DichVideoViet.zip; cd DichVideoViet;
     git init; git add -A; git commit -m init; git branch -M main;
     git remote add origin <link repo>; git push -u origin main
     (dang nhap bang Personal Access Token lam mat khau)
   - Hoac tren web GitHub: Add file > Create new file, go dung duong dan roi dan noi dung.
3) Tab Actions > "Build APK" > doi chay xong > Artifacts > tai DichVideoViet-apk > giai nen > cai APK.
   (Workflow tu tai file sherpa-onnx-1.13.7.aar, ban khong can lam gi them.)

B. DUNG
Mo app > chon video (va .srt neu co) > Bat dau dich.
Lan dau: app tu tai ~300 MB mo hinh (can mang 1 lan), sau do chay khong can mang
(tru lan dau dich 1 ngon ngu moi: ML Kit tai them ~30 MB cho ngon ngu do).
Ket qua: video + file .srt tieng Viet trong Android/data/vn.dichvideo/files/Movies/.

C. LUU Y
- Thu voi video ngan 1-2 phut truoc. Giu man hinh sang khi xu ly.
- Link giong doc tieng Viet nam o Models.kt (TTS_URL). Neu tai loi 404, doi ten mo hinh
  (vd vits-piper-vi_VN-25hours_single-low) hoac bao lai de sua.
