package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.OrganizationTag;
import com.yizhaoqi.smartpai.repository.OrganizationTagRepository;
import com.yizhaoqi.smartpai.service.DocumentService;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 闂備礁鎼崐绋棵洪敃鍌毼ラ柛宀€鍋涚粻宕囨喐瀹ュ鏄ユ俊銈呮噹闂傤垶鏌曟繛褍妫鍫曟⒑閹稿海鈽夐柤娲诲灦钘濋柍鍝勬噺閸嬬娀鏌涢埄鍏╂垿鎮楅鎴掔箚婵°倕顑囬幃鍏肩箾閺夋埈妯€鐎规洘顨婇幖褰掝敃閿濆棗鍔屽┑鐐舵彧缁插墽鍒掕箛娑辨晪妞ゆ巻鍋撻弫?
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    @Autowired
    private DocumentService documentService;
    
    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    /**
     * 闂備礁鎲＄敮鐐寸箾閳ь剚绻涢崨顓㈠弰鐎殿噮鍓熷畷鍫曞Ω閿濆倸浜鹃柛宀€鍋涢惌妤€鈹戦悩鎻掆偓绋款嚗閹剧粯鐓熼柍鍝勫枤閻掗箖鏌涘▍璇叉搐閺嬩線鏌ｅΔ鈧悧鍡欑矈?
     * 
     * @param fileMd5 闂備礁鎼崐绋棵洪敐鍛瀻妞ゃ垻鐓?
     * @param userId 闁荤喐绮庢晶妤呭箰閸涘﹥娅犻柣妯肩帛閸嬨劑鏌曟繝蹇曠暠闁绘挻妞孌
     * @param role 闂備焦妞垮鍧楀礉瀹ュ鏄ユ繛鎴炵閸犲棝鏌熼悜妯荤妞?
     * @return 闂備礁鎲＄敮鐐寸箾閳ь剚绻涢崨顓熸崳缂佽鲸甯￠獮妯尖偓鐢告櫜閸?
     */
    @DeleteMapping("/{fileMd5}")
    public ResponseEntity<?> deleteDocument(
            @PathVariable String fileMd5,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("DELETE_DOCUMENT");
        try {
            LogUtils.logBusiness("DELETE_DOCUMENT", userId, "闂佽浜介崕鏌ュ极瑜版帒绀嗛柡澶婄仢閻忊晠姊婚崟鈺佲偓鏍几閸愵煈娴栭柨婵嗙墱閸ょ偞鎱? fileMd5=%s, role=%s", fileMd5, role);
            documentService.deleteDocument(fileMd5, userId, role);
            
            LogUtils.logFileOperation(userId, "DELETE", fileMd5, fileMd5, "SUCCESS");
            monitor.end("Document deleted successfully");
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "Document deleted successfully");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            LogUtils.logUserOperation(userId, "DELETE_DOCUMENT", fileMd5, "FAILED_NOT_FOUND");
            monitor.end("Delete failed: document not found");
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.NOT_FOUND.value());
            response.put("message", "Document not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        } catch (SecurityException e) {
            LogUtils.logUserOperation(userId, "DELETE_DOCUMENT", fileMd5, "FAILED_PERMISSION_DENIED");
            monitor.end("Delete failed: permission denied");
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.FORBIDDEN.value());
            response.put("message", "Permission denied to delete this document");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("DELETE_DOCUMENT", userId, "闂佸憡甯炴繛鈧繛鍛叄瀵剟宕堕妸锝傚亾閸屾稑绶為弶鍫亯琚? fileMd5=%s", e, fileMd5);
            monitor.end("闂佸憡甯炴繛鈧繛鍛瀵板嫭娼忛銉? " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "Delete failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    /**
     * 闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉墯閸嬨劑鏌曟繝蹇曠暠闁绘挻娲熼弻娑樷枎濡櫣浠村銈嗘⒐閻楃姴顫忔繝姘兼晩闁绘劦鍓涢弳鐘绘⒑閸︻収鏆柛瀣崌閺岋繝宕煎┑鎰ч梺鍝勵儏閸燁垳绮嬮幒鏃€宕夐柛婵嗗娴犳岸鏌?
     * 
     * @param userId 闁荤喐绮庢晶妤呭箰閸涘﹥娅犻柣妯肩帛閸嬨劑鏌曟繝蹇曠暠闁绘挻妞孌
     * @param orgTags 闂備焦妞垮鍧楀礉瀹ュ鏄ユ繛鎴欏灩缁犮儵鏌嶈閸撴稒绂掗敃鍌涘€烽柣銏㈡暩閻撲胶绱撻崒娆戭槮婵炶绠撻幃銉╂晲閸モ晠鈹?
     * @return 闂備礁鎲￠悷顖炲垂閻㈠壊鏁婇柡宥庡幗閳锋帗銇勯弮鍌氫壕闁伙綁浜堕弻锟犲磼濮橆厾鐓戝┑鐐叉閸ㄨ棄鐣峰顑╂棃宕熼埞鎯т壕?
     */
    @GetMapping("/accessible")
    public ResponseEntity<?> getAccessibleFiles(
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_ACCESSIBLE_FILES");
        try {
            LogUtils.logBusiness("GET_ACCESSIBLE_FILES", userId, "闂備浇顫夋禍浠嬪磿閺屻儱鏋佺憸鐗堝笒缁€鍡涙煙瀹勬媽瀚扮紒鐙€鍨堕弻娑樷枎閹邦喖鈪归悷婊勫鐏忔瑩骞忕€ｎ喖閿ゆ俊銈勭筏缁鳖亪姊洪崫鍕偓绋棵洪敐鍛瀻闁靛繈鍨虹€氭岸姊洪崹顕呭剳婵犫偓? orgTags=%s", orgTags);
            
            List<FileUpload> files = documentService.getAccessibleFiles(userId, orgTags);
            
            LogUtils.logUserOperation(userId, "GET_ACCESSIBLE_FILES", "file_list", "SUCCESS");
            LogUtils.logBusiness("GET_ACCESSIBLE_FILES", userId, "闂備胶鎳撻悺銊╁礉閺囩喐鍙忔繛鎴欏灪閸ゆ梻鈧懓瀚妯煎緤閸ф鐓曟繛鍡樏悘銉︺亜閺冣偓閻楃姴顫忔繝姘兼晩闁兼亽鍎抽埀顒夊枛闇? fileCount=%d", files.size());
            monitor.end("Fetched accessible files");
            
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "Accessible files fetched");
            response.put("data", files);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_ACCESSIBLE_FILES", userId, "Failed to get accessible files", e);
            monitor.end("闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉墮閻銇勯弽銊х闁哥喎绻樺濠氬磼閵堝懏鐏嶉梺鍝勵儏閸燁垳绮嬮幒鏃€宕夐柟顓熷笂閺夘參鏌? " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "Failed to get accessible files: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    /**
     * 闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉墯閸嬨劑鏌曟繝蹇曠暠闁绘挻娲栭埥澶愬箻缁涜顣肩紓浣稿船閻栧ジ骞嗛崘顔肩妞ゆ巻鍋撴い锝嗙叀閺岋繝宕煎┑鎰ч梺鍝勵儏閸燁垳绮嬮幒鏃€宕夐柛婵嗗娴犳岸鏌?
     * 
     * @param userId 闁荤喐绮庢晶妤呭箰閸涘﹥娅犻柣妯肩帛閸嬨劑鏌曟繝蹇曠暠闁绘挻妞孌
     * @return 闂備焦妞垮鍧楀礉瀹ュ鏄ユ繛鎴炵懅閳绘柨鈹戦悩杈厡闁绘劕锕弻锝夊Ω閵夈儺浠鹃梺鍝勵儏閸燁垳绮嬮幒鏃€宕夐柛婵嗗娴犳岸鏌?
     */
    @GetMapping("/uploads")
    public ResponseEntity<?> getUserUploadedFiles(
            @RequestAttribute("userId") String userId) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_USER_UPLOADED_FILES");
        try {
            LogUtils.logBusiness("GET_USER_UPLOADED_FILES", userId, "Received request for uploaded files");
            
            List<FileUpload> files = documentService.getUserUploadedFiles(userId);
            
            // 闂佽绻愮换鎰板箰缁跺埐eUpload闂佸搫顦遍崕鎰板礈濮橆剛鏆﹂柛娆忣槺閳绘棃鏌涘┑鍡楊仼闁伙箑鐖奸弻娑橆潩閸楃偟顔塧gName闂備焦鐪归崝宀勫吹瀹勫
            List<Map<String, Object>> fileData = files.stream().map(file -> {
                Map<String, Object> dto = new HashMap<>();
                dto.put("fileMd5", file.getFileMd5());
                dto.put("fileName", file.getFileName());
                dto.put("totalSize", file.getTotalSize());
                dto.put("status", file.getStatus());
                dto.put("userId", file.getUserId());
                dto.put("public", file.isPublic());
                dto.put("createdAt", file.getCreatedAt());
                dto.put("mergedAt", file.getMergedAt());
                
                // 闂佽绻愮换鎰板绩缁屾笀Tag濠电偛顕慨瀵哥玻閹攦Id闂佸搫顦遍崕鎰板礈濮橆剛鏆﹂柛娆忣槺閳绘梻绱掗妸銉с€梘Name
                String orgTagName = getOrgTagName(file.getOrgTag());
                dto.put("orgTagName", orgTagName);
                
                return dto;
            }).collect(Collectors.toList());
            
            LogUtils.logUserOperation(userId, "GET_USER_UPLOADED_FILES", "file_list", "SUCCESS");
            LogUtils.logBusiness("GET_USER_UPLOADED_FILES", userId, "闂備胶鎳撻悺銊╁礉閺囩喐鍙忔繛鎴欏灪閸ゆ梻鈧懓瀚妯煎緤閸ф鐓熸い顐墮婵′粙鏌涢埡鍌溾姇缂佸顦扮换婵囨媴閾忕懓鈧偤姊洪崫鍕偓绋棵洪敐鍛瀻? fileCount=%d", files.size());
            monitor.end("Fetched uploaded files");
            
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "Uploaded files fetched successfully");
            response.put("data", fileData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_USER_UPLOADED_FILES", userId, "Failed to get uploaded files", e);
            monitor.end("闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉墯閸嬨劑鏌曟繝蹇曠暠闁绘挻娲栭埥澶愬箻缁涜顣肩紓浣稿船閻栫厧顕ｉ鍕倞闁挎梻鐡旈崑銊︾節閵忊€冲姸缂侇喖澧介幉? " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "Failed to get uploaded files: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    /**
     * 闂備礁鎼粔鐑斤綖婢跺﹦鏆ゅ〒姘ｅ亾鐎殿噮鍓熷畷鍫曟晜缁涘浠洪梻浣告啞閼瑰墽绮斿畷鍥╃當闁告侗鍠楁慨婊堟煠閹帒鍔滈柣搴枛闇?
     * 
     * @param fileName 闂備礁鎼崐绋棵洪敐鍛瀻闁靛繈鍊曠憴?
     * @param userId 闁荤喐绮庢晶妤呭箰閸涘﹥娅犻柣妯肩帛閸嬨劑鏌曟繝蹇曠暠闁绘挻妞孌  
     * @param orgTags 闂備焦妞垮鍧楀礉瀹ュ鏄ユ繛鎴欏灩缁犮儵鏌嶈閸撴稒绂掗敃鍌涘€烽柣銏㈡暩閻撲胶绱撻崒娆戭槮婵炶绠撻幃銉╂晲閸モ晠鈹?
     * @return 闂備礁鎼崐绋棵洪敐鍛瀻闁靛繈鍨虹亸搴ㄦ煕閺囥劌澧伴柣鏍憾閺岀喓鈧稒顭囨晶鐢告煛娴ｉ潻鍔熼柟椋庡█椤㈡稑顫濋鐔峰妼闂?
     */
    @GetMapping("/download")
    public ResponseEntity<?> downloadFileByName(
            @RequestParam String fileName,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("DOWNLOAD_FILE_BY_NAME");
        try {
            LogUtils.logBusiness("DOWNLOAD_FILE_BY_NAME", userId, "闂備浇顫夋禍浠嬪磿閺屻儱鏋佺憸鐗堝笒缁€鍡涙煟濡も偓閻楀﹪鎮楅娴庡綊鎮╅搹顐ｇ€紓浣诡殔閹冲繐顭囬鍫濈９闁绘洑绀佸▓鎻掆攽? fileName=%s", fileName);
            
            // 闂備礁鎼悮顐﹀磿閸欏鐝舵慨妞诲亾闁诡喕绮欐俊鎼佹晝閳ь剟鎮￠弴銏＄厱婵炲棙锚閻忋儲銇勯弮鈧悧鐘差潖婵犳凹鏁婇柣鎰靛墰閺嗙娀姊洪崫鍕偓绋棵洪敐鍛瀻?
            List<FileUpload> accessibleFiles = documentService.getAccessibleFiles(userId, orgTags);
            
            // 闂備礁鎼粔鐑斤綖婢跺﹦鏆ゅ〒姘ｅ亾鐎殿噮鍓熷畷鍫曟晜缁涘浠洪梻浣告啞閼瑰墽绮旈悽鍛婂亗闁跨喓濮寸粻銉╂煙椤栧棗鍟伴崺宥夋⒒娴ｈ绶茬紒澶庢硾閳绘捇骞嬮敃鈧Λ姗€鏌涢妷顖炴妞?
            Optional<FileUpload> targetFile = accessibleFiles.stream()
                    .filter(file -> file.getFileName().equals(fileName))
                    .findFirst();
                    
            if (targetFile.isEmpty()) {
                LogUtils.logUserOperation(userId, "DOWNLOAD_FILE_BY_NAME", fileName, "FAILED_NOT_FOUND");
                monitor.end("濠电偞鍨堕幐鎼侇敄閸緷褰掑炊閵娿儳绐為柡澶婄墱閸嬪顤傞梻浣瑰缁嬫帞鍠婂澶婂嚑闁告劏鏅濋々鐑芥煛閸垺鏆╅柣鐔哥箞閹鈽夊▍顓т簻閿曘垽顢旈崼婵堫吅闂佸搫绉查崝宥夋晸閵夆晜鐓涘璺猴工閺嗭絾淇婇幑鎰仩闁瑰嘲顑夊畷绋课旀笟濠勬そ");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "File not found or no access");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            FileUpload file = targetFile.get();
            
            // 闂備焦鐪归崹濠氬窗閹版澘鍨傛慨姗嗗幘閳绘棃鎮楅敐搴″箺缂佷椒鍗冲娲礈瑜嶆禍楣冩偨椤栨稒灏︽鐐差儔瀵粙顢楅崒婊庡敼闂備焦鎮堕崕鎶藉磻閵堝鈧倿鍩￠崘顏堚攺闂佽鍨庨崟顐熷亾閻┗L
            String downloadUrl = documentService.generateDownloadUrl(file.getFileMd5());
            
            if (downloadUrl == null) {
                LogUtils.logUserOperation(userId, "DOWNLOAD_FILE_BY_NAME", fileName, "FAILED_GENERATE_URL");
                monitor.end("Download failed: cannot generate URL");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                response.put("message", "Failed to generate download URL");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }
            
            LogUtils.logFileOperation(userId, "DOWNLOAD", file.getFileName(), file.getFileMd5(), "SUCCESS");
            LogUtils.logUserOperation(userId, "DOWNLOAD_FILE_BY_NAME", fileName, "SUCCESS");
            monitor.end("Download URL generated");
            
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "Download URL generated");
            response.put("data", Map.of(
                "fileName", file.getFileName(),
                "downloadUrl", downloadUrl,
                "fileSize", file.getTotalSize()
            ));
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            LogUtils.logBusinessError("DOWNLOAD_FILE_BY_NAME", userId, "闂備礁鎼崐绋棵洪敐鍛瀻闁靛骏绱曢埢鏃堟倵閿濆骸骞楃紒浣规緲椤潡骞嗛幍顔剧勘闁? fileName=%s", e, fileName);
            monitor.end("濠电偞鍨堕幐鎼侇敄閸緷褰掑炊閵娿儳绐為柡澶婄墱閸嬪顤? " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "Download failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    /**
     * 闂備礁鎼粔鐑斤綖婢跺﹦鏆ゅ☉鏃傚槻gId闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛锝嗗gName
     *
     * @param tagId 缂傚倸鍊风粈浣衡偓姘煎枤閸掓帡骞囬弶鍨敤闂佹悶鍎弲鈺呭礉閻⑩偓D
     * @return 缂傚倸鍊风粈浣衡偓姘煎枤閸掓帡骞囬弶鍨敤闂佹悶鍎弲鈺呭礉椤曗偓閺屾稑顫濋鍌傘倗鎮妸鈺傜叆婵炴垶顭堢€氱増銇勯埞顓炴搐閸戠姵绻濇繝鍌氼仼妞ゃ儲绻傞埥澶愬箻瀹曞泦銏ゆ煕閳轰胶鐒哥€规洘鑹鹃埢搴ㄥ箳閺冨偆鍞归梻浣规偠閸庢娊宕戝☉姘殰闁绘粎顒爂Id
     */
    private String getOrgTagName(String tagId) {
        if (tagId == null || tagId.isEmpty()) {
            return null;
        }
        
        try {
            Optional<OrganizationTag> tagOpt = organizationTagRepository.findByTagId(tagId);
            if (tagOpt.isPresent()) {
                return tagOpt.get().getName();
            } else {
                LogUtils.logBusiness("GET_ORG_TAG_NAME", "system", "闂備礁缍婂褑銇愰悙鐢电當濠㈣埖鍔曠粈鍡涙煙濞堝灝鏋ら柣顓烆儑缁辨挻鎷呴崨濠傤槱闂佺粯鐗徊璺ㄥ垝? tagId=%s", tagId);
                return tagId; // 濠电姷顣介埀顒€鍟块埀顒€缍婇幃妯诲緞閹邦剛顓奸梺鍛婄懀閸庢娊鎮峰┑瀣厱闁圭儤鏌ㄩ。濂告煟閺嶇數鎮肩紒顔炬暬楠炲棜顦抽柛濠傜－缁辨帗寰勬繝鍌滅泿缂備浇椴哥换鍐焽婵犳艾妫橀柟绋挎捣椤︺儵姊洪崨濠庣劷闁告柨绌琯Id
            }
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_ORG_TAG_NAME", "system", "闂備礁鎼悮顐﹀磿閹绢噮鏁嬫俊銈呭暟绾惧ジ鏌涢弴銊ュ箻闁活厼绻橀弻鈥愁吋閸涱喖绐涘銈嗗笧閸忔ê鐣烽妷銉悑闁搞儱妫涢崑鐐差嚗閸曨剚缍囨い鎰╁剾? tagId=%s", e, tagId);
            return tagId; // 闂備礁鎲￠悷锕傚垂瑜版帒鏋侀柟鍓х帛閻撱儲绻涢崱妯轰刊闁搞倖鐗犻弻锟犲礃閳轰礁濮哥紓浣虹帛閸ㄥ灝鐣烽崼鏇熷€烽柟缁樺笚閺嬬窂agId
        }
    }
} 
