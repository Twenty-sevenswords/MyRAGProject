package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.FileTypeValidationService;
import com.yizhaoqi.smartpai.service.UploadService;
import com.yizhaoqi.smartpai.service.UserService;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/upload")
public class UploadController {

    @Autowired
    private UploadService uploadService;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaConfig kafkaConfig;

    @Autowired
    private UserService userService;
    
    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private FileTypeValidationService fileTypeValidationService;
    
    public UploadController(UploadService uploadService, KafkaTemplate<String, Object> kafkaTemplate) {
        this.uploadService = uploadService;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 濠电偞鍨堕幐鎼佹晝閿濆洦顫曢柛顐ｆ礀濡﹢鏌涢妷顖炴妞ゆ劒绮欓弻娑㈠箳閹寸儐妫ゆ繝鈷€鍕⒌妤犵偞甯℃俊鐑藉Ψ閵壯呪枖
     *
     * @param fileMd5 闂備礁鎼崐绋棵洪敐鍛瀻闁靛繈鍊栭崕宥夋煕閹寸姴鐝?闂備胶顭堥敃锕傚Υ鐎ｎ剚顫曟繝闈涱儐閸嬨劑鏌曟繛鍨偓妤呮嚌妤ｅ啯鐓曢柣鎴濇閻忕喓绱掗弮鈧幐鍐差嚕娴犲鐐婃い蹇撴椤岸姊洪崫鍕偓绋棵洪敐鍛瀻?
     * @param chunkIndex 闂備礁鎲＄敮鎺懳涘☉娆愭珷闁哄稁鍋呴～鏇㈡煏韫囨洖啸缂佸苯娼￠弻銊モ槈濡偐鍔梺闈涙閸熸壆鍒掗崼銉ヤ紶闁告洦鍘炬瓏闂備礁鎲￠幐鍝ョ矓閹绢喖鍨傞幖娣妽閸嬪鏌涢妷銏℃珕闁伙絽宕湁闁挎繂鎳愯倴闂?
     * @param totalSize 闂備礁鎼崐绋棵洪敐鍛瀻闁靛繈鍊曠粻鎴︽偣閸パ呮勾濞存粣缍侀幃?
     * @param fileName 闂備礁鎼崐绋棵洪敐鍛瀻闁靛繈鍊曠憴?
     * @param totalChunks 闂備浇顕栭崜婵嬵敋瑜斿畷瑙勬償閵婏箑浜遍梺鎼炲劘閸斿秴鈻撻崼鏇熲拺?
     * @param orgTag 缂傚倸鍊风粈浣衡偓姘煎枤閸掓帡骞囬弶鍨敤闂佹悶鍎弲鈺呭礉椤曗偓閺屻劌鈽夊Ο鍨伃濡炪倐鏁崶褍鍤戝┑鐘诧工鐎氼厼顕ｉ幘缁樼厵缂佸瀵ч幖鎰版煟閿濆鐣虹€规洘鑹鹃埢搴㈡償閳锯偓閺嬪繘姊哄ú缁樺▏闁告柨閰ｅ顐﹀Χ婢跺﹦顓烘俊鐐差儏鐎涒晠鎮鹃崡鐐╂闁瑰墽鎳撻崥鍦磼椤旇偐肖缂佽鲸甯″畷鍫曞Ω瑜嶉悘杈╃磽?
     * @param isPublic 闂備礁鎼€氱兘宕规导鏉戠畾濞撴埃鍋撶€规洘顨婇、妤呭焵椤掑倹顫曢柟杈鹃檮閺咁剛鈧厜鍋撻柛鏇ㄥ幖缁楋繝鏌ｉ悩閬嶆闁稿﹥鎸鹃幏褰掓倿閹跺獥se
     * @param file 闂備礁鎲＄敮鎺懳涘☉娆愭珷闁哄稁鍘煎Λ姗€鏌涢妷顖炴妞ゆ劒绮欓幃妤呮偨濞堟寧鏁梺?
     * @return 闂佸搫顦弲婊堝蓟閵娿儍娲冀椤撶偟顦遍梺鍛婁緱閸樹粙宕曢幘鍨涘亾鐟欏嫮鎽冪€殿喗鎸剧划顓㈠磼濞戞﹩鍤ら梺缁樕戣ぐ鍐倵婵犳碍鐓熸俊銈傚亾婵炲眰鍊濋獮蹇涙偋閸懇鏋栨繝鐢靛С閼冲爼鎮欐繝鍥х骇闁冲搫鍊婚埥澶愭倵鐟欏嫬鈻曢柟顖氬暣瀹曠喖顢曢敐鍡楀妼闂?
     * @throws IOException 闁荤喐绮庢晶妤呭箰婵犳艾鍑犻柛鎰ㄦ櫇椤╃兘鏌熼鐐蹭喊闁搞們鍨介弻娑㈠箛椤撶姵鍣ч悷婊勬緲閸婂潡骞冩禒瀣亜闁稿繐鍚嬪▍鎾绘煟閻樺弶绁╅柛銊ュ船椤啴宕掗悙鑼唴濠电偞鍨堕敋婵?
     */
    @PostMapping("/chunk")
    public ResponseEntity<Map<String, Object>> uploadChunk(
            @RequestParam("fileMd5") String fileMd5,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("totalSize") long totalSize,
            @RequestParam("fileName") String fileName,
            @RequestParam(value = "totalChunks", required = false) Integer totalChunks,
            @RequestParam(value = "orgTag", required = false) String orgTag,
            @RequestParam(value = "isPublic", required = false, defaultValue = "false") boolean isPublic,
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("userId") String userId) throws IOException {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("UPLOAD_CHUNK");
        try {
            
            String fileType = getFileType(fileName);
            String contentType = file.getContentType();
            
            LogUtils.logBusiness("UPLOAD_CHUNK", userId, "闂備浇顫夋禍浠嬪磿閺屻儱鏋佺憸鐗堝笒缁€鍡涙煛婢跺﹦浠㈤柣搴☆煼閺岋絽螖閳ь剙煤閿濆洨绠旈柛灞剧☉椤曢亶鏌ｅΟ纭咁劅闁搞倗鍋為幈? fileMd5=%s, chunkIndex=%d, fileName=%s, fileType=%s, contentType=%s, fileSize=%d, totalSize=%d, orgTag=%s, isPublic=%s", 
                    fileMd5, chunkIndex, fileName, fileType, contentType, file.getSize(), totalSize, orgTag, isPublic);
        
        // 濠电姷顣介埀顒€鍟块埀顒€缍婇幃妯诲緞閹邦剙鐝樺銈呯箰閹冲酣鎯佽ぐ鎺撳€甸悷娆忓閻ㄦ垹绱掗鑲┬ょ紒杈ㄥ浮瀹曞爼濡歌閻忚京绱撴担鍦姇闁归攱绻勫Σ鎰攽鐎ｎ亞顦┑鐐叉鐢帞绮婚鐐寸厱婵炲棙鍔楁晶閬嶆煛娴ｉ潧鈧繈鐛€ｎ偒妲归幖娣灮閺嗙姵绻涢幋鐐村磩鐎规洜鏁搁崚鎺戭吋閸モ晝锛滈梺鎼炲劘閸斿酣宕归懖鈺冪＜?
        if (orgTag == null || orgTag.isEmpty()) {
            try {
                    LogUtils.logBusiness("UPLOAD_CHUNK", userId, "缂傚倸鍊风粈浣衡偓姘煎枤閸掓帡骞囬弶鍨敤闂佹悶鍎弲鈺呭礉椤曗偓閺岋繝宕奸锛勫嚬闁诲骸鐏氬銊╁焵椤掑喚娼愰柣鏃戝墰濡叉劕鈹戦崼婊呭墾濠电娀娼ч敃锕€危閹间焦鐓犻柟顖嗗啯些閻熸粍婢樺ú顓㈠箖娴犲惟闁挎洍鍋撻柣鎾存礀閳藉骞橀悷鏉挎優缂備緡鍠栫粔鍓佹閹烘鐐婇柕濞у啰浼囩紓? fileName=%s", fileName);
                String primaryOrg = userService.getUserPrimaryOrg(userId);
                orgTag = primaryOrg;
                    LogUtils.logBusiness("UPLOAD_CHUNK", userId, "闂備胶鎳撻悺銊╁礉閺囩喐鍙忔繛鎴欏灪閸ゆ梻鈧懓瀚妯煎緤閸ф鐓熸い顐墮婵′粙鏌涢埡鍌溾姇缂佸顦甸幊锟犲Χ閸モ晝鍘戠紓鍌氬€风粈浣该洪敃鍌涘亯闁挎繂娲ㄦす? fileName=%s, orgTag=%s", fileName, orgTag);
            } catch (Exception e) {
                    LogUtils.logBusinessError("UPLOAD_CHUNK", userId, "闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉墯閸嬨劑鏌曟繝蹇曠暠闁绘挻娲栭埥澶愬箻閻熸澘鎯炵紓渚囧枛缁夊墎妲愰幒妤€鐐婇柕濞у啰浼囩紓鍌欑劍缁嬫帡宕曢幎钘壩﹂柟瀵稿У鐎? fileName=%s", e, fileName);
                    monitor.end("闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉ｅ妿閳绘棃鏌ゆ慨鎰偓妤呮儗鐎ｎ剛纾藉ù锝呮啞婢跺嫰鏌ｉ弽鐢垫偧缂侇喚鏁婚獮鍡氼槹濞存粌銈搁幃? " + e.getMessage());
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                errorResponse.put("message", "Failed to resolve organization tag.");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
            }
        }
        
            LogUtils.logFileOperation(userId, "UPLOAD_CHUNK", fileName, fileMd5, "PROCESSING");
        
            uploadService.uploadChunk(fileMd5, chunkIndex, totalSize, fileName, file, orgTag, isPublic, userId);
            
            List<Integer> uploadedChunks = uploadService.getUploadedChunks(fileMd5);
            int actualTotalChunks = uploadService.getTotalChunks(fileMd5);
            double progress = calculateProgress(uploadedChunks, actualTotalChunks);
            
            LogUtils.logBusiness("UPLOAD_CHUNK", userId, "闂備礁鎲＄敮鎺懳涘☉娆愭珷闁哄秲鍔庨埢鏂库攽閻樿精鍏岄柣鎰躬閺岀喓鈧稒锚婵洤鈹? fileMd5=%s, fileName=%s, fileType=%s, chunkIndex=%d, 闂佸搫顦弲婊呯矙閹寸姭鍋?%.2f%%", 
                    fileMd5, fileName, fileType, chunkIndex, progress);
            monitor.end("Chunk uploaded successfully");
            
            // 闂備礁鎼鍛偓姘煎墰缁辨捇骞樼拠鍙夌€梺缁橆殔閻楀棛绮婇敃鍌涘€甸柣銏☆問閺€浼存煢?
            Map<String, Object> data = new HashMap<>();
            data.put("uploaded", uploadedChunks);
            data.put("progress", progress);
            
            // 闂備礁鎼鍛偓姘煎墰缁辨捇骞橀崜浣猴紲闂佺粯鏌ㄦ晶搴敂闁秵鐓曟繝闈涙瀛濈紓浣靛妽閻撯€愁嚕娴犲鍋傞幖杈剧悼椤?
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "Chunk uploaded successfully");
            response.put("data", data);
            
            return ResponseEntity.ok(response);
        } catch (UploadService.FileValidationException e) {
            LogUtils.logBusinessError("UPLOAD_CHUNK", userId, "闂佸搫鍊稿ú锝呪枎閵忋垻灏甸悹鍥皺閳ь剛鍏樺浠嬫晸閻橀潧鐭楁繝銏″劶缁墽鎲? fileMd5=%s, fileName=%s", e, fileMd5, fileName);
            monitor.end("闂佸搫鍊稿ú锝呪枎閵忋垻灏甸悹鍥皺閳ь剛鍏樺浠嬫晸閻橀潧鐭楁繝銏″劶缁墽鎲? " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", HttpStatus.BAD_REQUEST.value());
            errorResponse.put("message", "Unsupported file type. Please upload a supported document format.");
            errorResponse.put("fileType", e.getFileType());
            errorResponse.put("supportedTypes", e.getSupportedTypes());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        } catch (Exception e) {
            String fileType = getFileType(fileName);
            LogUtils.logBusinessError("UPLOAD_CHUNK", userId, "闂備礁鎲＄敮鎺懳涘☉娆愭珷闁哄秲鍔庨埢鏂库攽閻樿精鍏岄柣鎰工椤潡骞嗛幍顔剧勘闁? fileMd5=%s, fileName=%s, fileType=%s, chunkIndex=%d", e, fileMd5, fileName, fileType, chunkIndex);
            monitor.end("闂備礁鎲＄敮鎺懳涘☉娆愭珷闁哄秲鍔庨埢鏂库攽閻樿精鍏岄柣鎰工椤潡骞嗛幍顔剧勘闁? " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            errorResponse.put("message", resolveUploadErrorMessage(e, "Chunk upload failed. Please try again."));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * 闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉墮濡﹢鏌涢妷顖炴妞ゆ劘妫勯埥澶愬箻缁涜顣肩紓浣稿船閻栧ジ骞冮埡鍛殝缁剧増锚娴滄儳霉閿濆洦鍤€濠㈣泛绉归弻?
     *
     * @param fileMd5 闂備礁鎼崐绋棵洪敐鍛瀻闁靛繈鍊栭崕宥夋煕閹寸姴鐝?闂備胶顭堥敃锕傚Υ鐎ｎ剚顫曟繝闈涱儐閸嬨劑鏌曟繛鍨偓妤呮嚌妤ｅ啯鐓曢柣鎴濇閻忕喓绱掗弮鈧幐鍐差嚕娴犲鐐婃い蹇撴椤岸姊洪崫鍕偓绋棵洪敐鍛瀻?
     * @return 闂佸搫顦弲婊堝蓟閵娿儍娲冀椤撶偟顦遍梺鍛婁緱閸樹粙宕曢幘鍨涘亾鐟欏嫮鎽冪€殿喗鎸剧划顓㈠磼濞戞﹩鍤ら梺缁樕戣ぐ鍐倵婵犳碍鐓熸俊銈傚亾婵炲眰鍊濋獮蹇涙偋閸懇鏋栨繝鐢靛С閼冲爼鎮欐繝鍥х骇闁冲搫鍊婚埥澶愭倵鐟欏嫬鈻曢柟顖氬暣瀹曠喖顢曢敐鍡楀妼闂?
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getUploadStatus(@RequestParam("file_md5") String fileMd5) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_UPLOAD_STATUS");
        try {
            // 闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉墮濡﹢鏌涢妷顖炴妞ゆ劘妫勮彁闁搞儻绲芥晶鎻捗?
            String fileName = "unknown";
            String fileType = "unknown";
            try {
                Optional<FileUpload> fileUpload = fileUploadRepository.findByFileMd5(fileMd5);
                if (fileUpload.isPresent()) {
                    fileName = fileUpload.get().getFileName();
                    fileType = getFileType(fileName);
                }
            } catch (Exception e) {
                // 闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉墮濡﹢鏌涢妷顖炴妞ゆ劘妫勮彁闁搞儻绲芥晶鎻捗归悡搴㈠櫤鐎垫澘瀚蹇涱敃閵夋劖娲栭埥澶愬箻瀹曞泦銏犫攽閸屾稓澧电€规洦鍋勯濂稿炊椤噯绠撻弻鐔碱敄鐠恒劌濡介梺缁樻惈缁辨洟骞忛悩浼欑稏妞ゆ柨澧介ˇ顔剧磽閸屾瑧绉柛娑卞灡閺嗙増绻濋姀锝嗙【閻庢凹鍣ｉ獮?
                LogUtils.logBusiness("GET_UPLOAD_STATUS", "system", "闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉墮濡﹢鏌涢妷顖炴妞ゆ劘妫勮彁闁搞儻绲芥晶鎻捗归悡搴㈠櫤鐎垫澘瀚蹇涱敃閵夋劖娲熼弻銊モ槈濡警娈繝鐢靛仜濞差參骞冩禒瀣╅柍鍝勫暙缁楋繝鏌ｉ悩閬嶆闁稿﹥娲熼崺鈧? fileMd5=%s, 闂傚倷鐒︾€笛囨偡閵娾晩鏁?%s", fileMd5, e.getMessage());
            }
            
            LogUtils.logBusiness("GET_UPLOAD_STATUS", "system", "闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉墮濡﹢鏌涢妷顖炴妞ゆ劘妫勯埥澶愬箻缁涜顣肩紓浣稿船閻栧ジ骞冮埡鍛殝缁剧増锚娴? fileMd5=%s, fileName=%s, fileType=%s", fileMd5, fileName, fileType);
            
            List<Integer> uploadedChunks = uploadService.getUploadedChunks(fileMd5);
            int totalChunks = uploadService.getTotalChunks(fileMd5);
            double progress = calculateProgress(uploadedChunks, totalChunks);
            
            LogUtils.logBusiness("GET_UPLOAD_STATUS", "system", "闂備礁鎼崐绋棵洪敐鍛瀻闁靛骏绱曢埢鏂库攽閻樿精鍏岄柣鎰躬閺岋絽螣閸喚鍘梺? fileMd5=%s, fileName=%s, fileType=%s, 闁诲氦顫夐悺鏇烆嚕閹惧墎绠旈柛灞剧☉椤?%d/%d, 闂佸搫顦弲婊呯矙閹寸姭鍋?%.2f%%", 
                    fileMd5, fileName, fileType, uploadedChunks.size(), totalChunks, progress);
            monitor.end("Upload status fetched successfully");
            
            // 闂備礁鎼鍛偓姘煎墰缁辨捇骞樼拠鍙夌€梺缁橆殔閻楀棛绮婇敃鍌涘€甸柣銏☆問閺€浼存煢?
            Map<String, Object> data = new HashMap<>();
            data.put("uploaded", uploadedChunks);
            data.put("progress", progress);
            data.put("fileName", fileName);
            data.put("fileType", fileType);
            
            // 闂備礁鎼鍛偓姘煎墰缁辨捇骞橀崜浣猴紲闂佺粯鏌ㄦ晶搴敂闁秵鐓曟繝闈涙瀛濈紓浣靛妽閻撯€愁嚕娴犲鍋傞幖杈剧悼椤?
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "Upload status fetched successfully");
            response.put("data", data);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_UPLOAD_STATUS", "system", "闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉墮濡﹢鏌涢妷顖炴妞ゆ劘妫勯埥澶愬箻缁涜顣肩紓浣稿船閻栧ジ骞冮埡鍛殝缁剧増锚娴滄儳霉閿濆棙鍋犲ù婊冦偢閹? fileMd5=%s", e, fileMd5);
            monitor.end("闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉ｅ妿閳绘柨鈹戦悩杈厡闁绘劕锕弻锝呂熼崹顔惧帿闂侀€炲苯鍘哥紒鈧笟鈧俊鐢稿箣閻樺啿顏? " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            errorResponse.put("message", "Failed to get upload status.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * 闂備礁鎲￠懝楣冩偋閸℃稒鍤愰柣鏃傚帶濡﹢鏌涢妷顖炴妞ゆ劒绮欓弻娑㈠箳閹寸儐妫ゆ繝鈷€鍕⒌妤犵偞甯℃俊鐑藉Ψ閵壯呪枖
     *
     * @param request 闂備礁鎲￠悧鏇㈠箠鎼淬劌绠氶柛顐犲劚濡﹢鏌涢妷顖炴妞ゆ劗绁獶5闂備礁鎲＄划宀勫嫉椤掑嫬鍑犻柛鎰ㄦ櫇椤╃兘鎮归搹鐟板妺闁稿﹥濞婇弻锝夊Ω閵夈儺浼傚銈嗘处閸撴瑦鏅ラ梺绋挎湰閻熝呯矓?
     * @param userId 闁荤喐绮庢晶妤呭箰閸涘﹥娅犻柣妯肩帛閸嬨劑鏌曟繝蹇曠暠闁绘挻妞孌
     * @return 闂佸搫顦弲婊堝蓟閵娿儍娲冀椤撶偟顦遍梺鍛婁緱閸樹粙宕曢幘缁樼厱婵﹩鍓涙晶鏇㈡煠閼姐倕鏋涚€规洏鍎查幆鏃堝閿涘嫧鍋撻娴庡綊鎮╅崘鎻掑Ц濡炪倖姊归悧鐘差潖婵犳碍鍋ㄧ€广儳鐔侀梻浣圭湽閸斿瞼鈧凹鍓熼獮鏍煥鐎ｎ偄鍔?
     */
    @Transactional
    @PostMapping("/merge")
    public ResponseEntity<Map<String, Object>> mergeFile(
            @RequestBody MergeRequest request,
            @RequestAttribute("userId") String userId) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("MERGE_FILE");
        try {
            String fileType = getFileType(request.fileName());
            LogUtils.logBusiness("MERGE_FILE", userId, "闂備浇顫夋禍浠嬪磿閺屻儱鏋佺憸鐗堝笒缁€鍡涙煛婢跺顕滈柛濞垮€栭〃銉╂倷鐠鸿櫣鍘梺鍝勵儏閸燁垳绮嬮幒妤€绠氶梺顓ㄧ畱濞堟彃鈹? fileMd5=%s, fileName=%s, fileType=%s", 
                    request.fileMd5(), request.fileName(), fileType);
            
            // 婵犵妲呴崑鈧柛瀣崌閺岋紕浠︾拠鎻掑闂佸搫顑呴崯顖滅矉閹烘梹宕夐柧蹇氼潐濞堫噣姊烘潪鎵槮闁宦板姂閸┾偓妞ゆ埈鍓欓崯顖溾偓姘叀閺屸剝寰勬繝鍌涙濠?
            LogUtils.logBusiness("MERGE_FILE", userId, "婵犵妲呴崑鈧柛瀣崌閺岋紕浠︾拠鎻掑闂佸搫顑呴崯顖滅矉閹烘绠氶梺顓ㄧ畱閺佸綊鎮峰鍛暭婵炲弶鐗犻獮蹇斿閺夋垹楠囬梺鍛婂姦閸犳顢? fileMd5=%s, fileName=%s", request.fileMd5(), request.fileName());
            FileUpload fileUpload = fileUploadRepository.findByFileMd5AndUserId(request.fileMd5(), userId)
                    .orElseThrow(() -> {
                        LogUtils.logUserOperation(userId, "MERGE_FILE", request.fileMd5(), "FAILED_FILE_NOT_FOUND");
                        return new RuntimeException("File record not found");
                    });
                    
            // 缂備胶铏庨崣搴ㄥ窗閺囩姵宕叉慨妯垮煐閸嬨劑鏌曟繝蹇曠暠闁绘挻娲熼弻锟犲醇濠垫劖效濠电偟鍘ч悧鎾愁潖娴犲绠涙い鎾跺枑閸庛儲绻涙潏鍓хɑ缂侇喖绻橀、姘潩鐠虹儤顥濋梺鎼炲劵闂勫嫰顢?
            if (!fileUpload.getUserId().equals(userId)) {
                LogUtils.logUserOperation(userId, "MERGE_FILE", request.fileMd5(), "FAILED_PERMISSION_DENIED");
                LogUtils.logBusiness("MERGE_FILE", userId, "闂備礁鎼ˇ顖炲疮閺夋埈鐎堕柣鎴烇供濞堢晫鈧厜鍋撻柛鎰典簼椤秵绻濋姀鈥冲姸缂侇喖澧介幉? 闂佽绻愮换鎴犳崲閸℃稒鍎婃い鏍仜鐟欙箓鏌涢锝囩畾閻庢碍鐟ラ埥澶愬箻瀹曞泦銏ゆ煟椤愶缉鎴犵矉瀹ュ棙鍎熼柕鍫濇噺椤斿洭鎮楃憴鍕憙濞存粠鍓欓埢鎾诲箣閿曗偓濡﹢鏌涢妷顖炴妞? fileMd5=%s, fileName=%s, 闂佽楠稿﹢閬嶅磻閵堝拋鐎舵い鏍仜缁犮儵鏌嶈閸撶喎顕ｉ崹顐㈢窞閹煎瓨锚娴?%s", 
                        request.fileMd5(), request.fileName(), fileUpload.getUserId());
                monitor.end("Merge failed: permission denied");
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("code", HttpStatus.FORBIDDEN.value());
                errorResponse.put("message", "Permission denied for this file");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
            }
            
            LogUtils.logBusiness("MERGE_FILE", userId, "闂備礁鎼ˇ顖炲疮閺夋埈鐎堕柣鎴烇供濞堢晫鈧厜鍋撻柛鎰典簼椤秹姊绘笟鍥т簼濞ｅ洩娅ｉ幑銏ゅ礃椤旇姤娅栭柣蹇曞仧閺咁偆澹曠拠娴嬫斀妞ゆ梻鈷堥崕蹇涙煙椤斿ジ鍙勯柣娑卞櫍閹虫粓宕归銈傚亾椤旀祹? fileMd5=%s, fileName=%s, fileType=%s", request.fileMd5(), request.fileName(), fileType);
            
            // 婵犵妲呴崑鈧柛瀣崌閺岋紕浠︾拠鎻掑闂佹悶鍔岄柊锝夊箖瑜斿畷鍫曞Ω锜洪敃鍌涚厱婵ê澧介悾閬嶆煕濞嗗繒绠婚柡灞界墦婵℃悂濡堕崶鈺傚暟濠电偟顥愰崑鎰叏閹绢喗鍋╂い鎺戝缁?
            List<Integer> uploadedChunks = uploadService.getUploadedChunks(request.fileMd5());
            int totalChunks = uploadService.getTotalChunks(request.fileMd5());
            LogUtils.logBusiness("MERGE_FILE", userId, "闂備礁鎲＄敮鎺懳涘☉娆愭珷闁哄秲鍔庨埢鏂库攽閻樿精鍏岄柣鎰躬閺岋絽螣閸喚鍘梺? fileMd5=%s, fileName=%s, 闁诲氦顫夐悺鏇烆嚕閹惧墎绠旈柛灞剧☉椤?%d/%d", 
                    request.fileMd5(), request.fileName(), uploadedChunks.size(), totalChunks);
            
            if (uploadedChunks.size() < totalChunks) {
                LogUtils.logUserOperation(userId, "MERGE_FILE", request.fileMd5(), "FAILED_INCOMPLETE_CHUNKS");
                monitor.end("Merge failed: incomplete chunks");
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("code", HttpStatus.BAD_REQUEST.value());
                errorResponse.put("message", "Cannot merge because chunks are incomplete");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
            }

            // 闂備礁鎲￠懝楣冩偋閸℃稒鍤愰柣鏃傚帶濡﹢鏌涢妷顖炴妞?
            LogUtils.logBusiness("MERGE_FILE", userId, "闁诲孩顔栭崰鎺楀磻閹炬枼鏀芥い鏃傗拡閸庡繘鏌熼濂稿弰闁绘侗鍣ｉ幊婊堝垂椤愩倐鍋撻娴庡綊鎮╅悜妯笺€愰梺鎼炲妼闁帮綁骞? fileMd5=%s, fileName=%s, fileType=%s, 闂備礁鎲＄敮鎺懳涘☉娆愭珷闁哄稁鍘奸弸浣该归崗鍏肩稇婵?%d", request.fileMd5(), request.fileName(), fileType, totalChunks);
            String objectUrl = uploadService.mergeChunks(request.fileMd5(), request.fileName());
            LogUtils.logFileOperation(userId, "MERGE", request.fileName(), request.fileMd5(), "SUCCESS");

            // 闂備礁鎲￠悷锕傚垂閸ф鐒垫い鎴炲椤︾兘鏌熼獮鍨伈鐎规洘绻堥崹楣冨礃閼碱剙甯?Kafka闂備焦瀵х粙鎴︽嚐椤栨壕鍋撻崹顐€跨€规洏鍎甸、鏇㈠閵忊剝顔呴梻浣芥〃缁€渚€顢氶鐐╂灁闁硅揪绠戠痪褔鏌涢幇闈涙灍妞ゅ孩鐟ヨ彁闁搞儻绲芥晶鎻捗?
            LogUtils.logBusiness("MERGE_FILE", userId, "闂備礁鎲＄敮妤冪矙閹寸姷纾介柟鎹愵嚙濡﹢鏌涢妷顖炴妞ゆ劘妫勯…璺ㄦ崉閸濆嫷浼€闂佽鍠栭敃銉х矉閹烘梹瀚氶柟缁樺俯濞? fileMd5=%s, fileName=%s, fileType=%s, orgTag=%s, isPublic=%s", 
                    request.fileMd5(), request.fileName(), fileType, fileUpload.getOrgTag(), fileUpload.isPublic());
            
            FileProcessingTask task = new FileProcessingTask(
                    request.fileMd5(),
                    objectUrl,
                    request.fileName(),
                    fileUpload.getUserId(),
                    fileUpload.getOrgTag(),
                    fileUpload.isPublic()
            );
            
            LogUtils.logBusiness("MERGE_FILE", userId, "闂備礁鎲￠悷锕傚垂閸ф鐒垫い鎴ｆ硶閸斿秹鏌＄€ｎ亜鏆炵紒鍌涘笧閹瑰嫭绗熼娴剁喖姊洪懡銈呬粶婵☆偅妫冮獮鎴﹀閵堝懐顦梺鐐藉劚閸熷潡鎮℃笟绛ka(濠电偛鐡ㄧ划宀勵敄閸涱喗鍙?: topic=%s, fileMd5=%s, fileName=%s", 
                    kafkaConfig.getFileProcessingTopic(), request.fileMd5(), request.fileName());
            kafkaTemplate.executeInTransaction(kt -> {
                kt.send(kafkaConfig.getFileProcessingTopic(), task);
                return true;
            });
            LogUtils.logBusiness("MERGE_FILE", userId, "闂備礁鎼崐绋棵洪敐鍛瀻闁靛繆鈧磭绐為梺鍛婃处閸樹粙宕愰悙瑁佸綊鎮╂笟顖氭婵犳鍠氶崰搴敊韫囨稑绠甸柟鐑樺灩瀹€娑㈡⒒? fileMd5=%s, fileName=%s, fileType=%s", request.fileMd5(), request.fileName(), fileType);

            // 闂備礁鎼鍛偓姘煎墰缁辨捇骞樼拠鍙夌€梺缁橆殔閻楀棛绮婇敃鍌涘€甸柣銏☆問閺€浼存煢?
            Map<String, Object> data = new HashMap<>();
            data.put("object_url", objectUrl);
            
            // 闂備礁鎼鍛偓姘煎墰缁辨捇骞橀崜浣猴紲闂佺粯鏌ㄦ晶搴敂闁秵鐓曟繝闈涙瀛濈紓浣靛妽閻撯€愁嚕娴犲鍋傞幖杈剧悼椤?
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "File merged successfully and queued to Kafka");
            response.put("data", data);
            
            LogUtils.logUserOperation(userId, "MERGE_FILE", request.fileMd5(), "SUCCESS");
            monitor.end("File merged successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            String fileType = getFileType(request.fileName());
            LogUtils.logBusinessError("MERGE_FILE", userId, "闂備礁鎼崐绋棵洪敐鍛瀻闁靛繈鍊曠憴锕傛煕椤愶絿绠氶悗姘懃椤潡骞嗛幍顔剧勘闁? fileMd5=%s, fileName=%s, fileType=%s", e, 
                    request.fileMd5(), request.fileName(), fileType);
            monitor.end("闂備礁鎼崐绋棵洪敐鍛瀻闁靛繈鍊曠憴锕傛煕椤愶絿绠氶悗姘懃椤潡骞嗛幍顔剧勘闁? " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            errorResponse.put("message", resolveUploadErrorMessage(e, "File merge failed. Please try again."));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * 闂佽崵濮崇欢銈囨閺囥垺鍋╃紓浣诡焽閳绘柨鈹戦悩杈厡闁绘劕锕鍫曞煛閸屾壕妲堥柣?
     *
     * @param uploadedChunks 闁诲氦顫夐悺鏇烆嚕閹惧墎绠旈柛灞剧☉椤曢亶鏌ｅΟ铏逛粵闁伙綁浜堕弻娑㈠箳閹寸儐妫ゆ繝鈷€鍕⒌鐎规洘鑹捐灃闁告洍鏂侀崑?
     * @param totalChunks 闂備浇顕栭崜婵嬵敋瑜斿畷瑙勬償閵婏箑浜遍梺鎼炲劘閸斿秴鈻撻崼鏇熲拺?
     * @return 闂佸搫顦弲婊堝蓟閵娿儍娲冀閵娧€鏋栨繝鐢靛С閼冲爼鎮欐繝鍥х骇闁冲搫鍊婚埥澶愭倵鐟欏嫬鈻曢柟顖氬暣瀹曠喖顢橀悤浣锋樊闂備礁鎲＄敮鎺懳涘▎鎾村剨?
     */
    private double calculateProgress(List<Integer> uploadedChunks, int totalChunks) {
        if (totalChunks == 0) {
            LogUtils.logBusiness("CALCULATE_PROGRESS", "system", "闂佽崵濮崇欢銈囨閺囥垺鍋╃紓浣诡焽閳绘柨鈹戦悩杈厡闁绘劕锕鍫曞煛閸屾壕妲堥柣搴ゎ潐婵炲﹤顕ｉ鈧幊婊呭枈濡桨澹曢柣鐘叉处瑜板啴鎮楁繝姘厽婵°倐鍋撴繛璇х畵瀵娊鎮㈤崫銉㈡灃?");
            return 0.0;
        }
        return (double) uploadedChunks.size() / totalChunks * 100;
    }

    /**
     * 闂備礁鎲￠懝楣冩偋閸℃稒鍤愰柣鏃傚劋鐎氭岸姊洪崹顕呭剳婵犫偓閹绢喗鐓熼柕濞垮劚椤忣剛绱掗崜浣告灈鐎规洘绻堥崺锟犲礃椤撗勮晧闂備焦瀵х粙鎴︽嚐椤栨壕鍋撻崹顐€跨€规洏鍎甸、鏇㈡晲閸℃瑢鍋撻娴庡綊鎮╅幓鎺濇殺濠电偛鐗婇崢绋?闂備胶顭堥敃锕傚储瑜旈獮蹇斿閺夋垶顥濋梺鎼炲劵闂勫嫰顢曟禒瀣厱?
     */
    public record MergeRequest(String fileMd5, String fileName) {}

    /**
     * 闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉墮缂佲晜銇勯弽銊р槈闁伙富鍣ｉ弻锝夊Ω閵夈儺浠鹃梺鍝勵儏閸燁垳绮嬮幒妤€绀堝ù锝囩摂濞煎爼姊洪幖鐐插妞ゆ垵鎳樺畷褰掝敂閸℃ê浠掗梺闈涱煭缁犳垶寰勫澶嬬厱?
     *
     * @return 闂佸搫顦弲婊堝蓟閵娿儍娲冀椤撶偟鍝楀銈嗙墬缁嬫垿鎯侀鈧弻锝夊Ω閵夈儺浠鹃梺鍝勵儏閸燁垳绮嬮幒妤€绀堝ù锝囩摂濞煎爼姊洪幖鐐插妞ゆ垵瀚粚杈ㄧ節閸パ呯暢?
     */
    @GetMapping("/supported-types")
    public ResponseEntity<Map<String, Object>> getSupportedFileTypes() {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_SUPPORTED_TYPES");
        try {
            LogUtils.logBusiness("GET_SUPPORTED_TYPES", "system", "Fetching supported file types");
            
            Set<String> supportedTypes = fileTypeValidationService.getSupportedFileTypes();
            Set<String> supportedExtensions = fileTypeValidationService.getSupportedExtensions();
            
            // 闂備礁鎼鍛偓姘煎墰缁辨捇骞樼拠鍙夌€梺缁橆殔閻楀棛绮婇敃鍌涘€甸柣銏☆問閺€浼存煢?
            Map<String, Object> data = new HashMap<>();
            data.put("supportedTypes", supportedTypes);
            data.put("supportedExtensions", supportedExtensions);
            data.put("description", "Supported file types that can be parsed and indexed.");
            
            // 闂備礁鎼鍛偓姘煎墰缁辨捇骞橀崜浣猴紲闂佺粯鏌ㄦ晶搴敂闁秵鐓曟繝闈涙瀛濈紓浣靛妽閻撯€愁嚕娴犲鍋傞幖杈剧悼椤?
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "Supported file types fetched successfully");
            response.put("data", data);
            
            LogUtils.logBusiness("GET_SUPPORTED_TYPES", "system", "闂備胶鎳撻悺銊╁礉閺囩喐鍙忔繛鎴炵懄娴溿倝鏌￠崒娑橆嚋缂佲偓閳ь剟姊虹悰鈥充壕濡炪倖鐗楃粙鎴︽儊椤曗偓閺岋綁濡搁妷銉痪闂佸搫顑呴崯顖滅矉閹烘绀堝ù锝囩摂濞煎爼姊? 缂傚倷绶￠崑澶愵敋瑜旈幃妤呮倻閽樺鐎繛鏉戝悑濞兼瑥鈻?%d, 闂備礁婀遍。浠嬪疾濞戙垺鍎撶€广儱顦憴锔锯偓骞垮劚濡瑥鈻撻崼鏇熲拺?%d", 
                    supportedTypes.size(), supportedExtensions.size());
            monitor.end("Supported file types fetched");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_SUPPORTED_TYPES", "system", "Failed to fetch supported file types", e);
            monitor.end("闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉墮缂佲晜銇勯弽銊р槈闁伙富鍣ｉ弻锝夊Ω閵夈儺浠鹃梺鍝勵儏閸燁垳绮嬮幒妤€绀堝ù锝囩摂濞煎爼姊洪幖鐐插妞ゆ垵鎳樻俊鐢稿箣閻樺啿顏? " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            errorResponse.put("message", "Failed to fetch supported file types: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * 闂備礁鎼粔鐑斤綖婢跺﹦鏆ゅ〒姘ｅ亾鐎殿噮鍓熷畷鍫曟晜缁涘浠洪梻浣告啞閼瑰墽绮旈悜鐣屽祦闊洦绋戦惌妤呮煛瀹ュ啫濡介柣搴枛闇夐柣妯诲絻椤ｆ娊鎮跺鐓庝喊鐎?
     *
     * @param fileName 闂備礁鎼崐绋棵洪敐鍛瀻闁靛繈鍊曠憴?
     * @return 闂備礁鎼崐绋棵洪敐鍛瀻闁靛繈鍨荤亸鐢告偣閸ヮ亜鐨洪柍?
     */

    private String resolveUploadErrorMessage(Throwable throwable, String fallbackMessage) {
        if (throwable == null) {
            return fallbackMessage;
        }

        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        String rootMessage = root.getMessage();
        if (rootMessage == null || rootMessage.isBlank()) {
            return fallbackMessage;
        }

        String normalized = rootMessage.toLowerCase(Locale.ROOT);
        if (normalized.contains("specified bucket does not exist")) {
            return "Storage bucket is missing. Please contact the administrator.";
        }
        if (normalized.contains("access denied")) {
            return "Storage access denied. Please contact the administrator.";
        }

        return fallbackMessage;
    }
    private String getFileType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unknown";
        }
        
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return "unknown";
        }
        
        String extension = fileName.substring(lastDotIndex + 1).toLowerCase();
        
        // 闂備礁鎼粔鐑斤綖婢跺﹦鏆ゅ〒姘ｅ亾鐎殿噮鍓熷畷鍫曟晜缁涘浠洪梻浣告贡椤ｄ粙寮插☉銏″創鐎广儱顦憴锔锯偓骞垮劚閻楀繒绮婚弻銉︾厱闁靛／鍐ф勃闂佸搫顑呴崯顖滅矉閹烘绀堝ù锝囩摂濞煎爼姊?
        switch (extension) {
            case "pdf":
                return "PDF";
            case "doc":
            case "docx":
                return "Word";
            case "xls":
            case "xlsx":
                return "Excel";
            case "ppt":
            case "pptx":
                return "PowerPoint";
            case "txt":
                return "Text";
            case "md":
                return "Markdown";
            case "jpg":
            case "jpeg":
                return "JPEG Image";
            case "png":
                return "PNG Image";
            case "gif":
                return "GIF Image";
            case "bmp":
                return "BMP Image";
            case "svg":
                return "SVG Image";
            case "mp4":
                return "MP4 Video";
            case "avi":
                return "AVI Video";
            case "mov":
                return "MOV Video";
            case "wmv":
                return "WMV Video";
            case "mp3":
                return "MP3 Audio";
            case "wav":
                return "WAV Audio";
            case "flac":
                return "FLAC Audio";
            case "zip":
                return "ZIP Archive";
            case "rar":
                return "RAR Archive";
            case "7z":
                return "7Z Archive";
            case "tar":
                return "TAR Archive";
            case "gz":
                return "GZ Archive";
            case "json":
                return "JSON";
            case "xml":
                return "XML";
            case "csv":
                return "CSV";
            case "html":
            case "htm":
                return "HTML";
            case "css":
                return "CSS";
            case "js":
                return "JavaScript";
            case "java":
                return "Java";
            case "py":
                return "Python";
            case "cpp":
            case "c":
                return "C/C++";
            case "sql":
                return "SQL";
            default:
                return extension.toUpperCase() + " File";
        }
    }
}



