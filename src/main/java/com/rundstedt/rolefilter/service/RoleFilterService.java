package com.rundstedt.rolefilter.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rundstedt.rolefilter.resultEntity.*;
import com.rundstedt.util.GenHttpHeader;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.rundstedt.rolefilter.resultEntity.Wuxue.is99Wuxue;
import static com.rundstedt.rolefilter.resultEntity.Wuxue.isGupuWuxue;

@Service
public class RoleFilterService {
    @Autowired
    private GenHttpHeader genHttpHeader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${scrapy-roleByFilter-endTxt}")
    private String endTxt;

    private String woniuJishiUrl = "http://jishi.woniu.com/9yin/";

    /**
     * 获取服务器列表
     * @return
     */
    public List<ServerResult> findServerList(){
        RestTemplate restTemplate=new RestTemplate(); //创建请求
        Map<String,String> params=new HashMap<>(); //创建参数表
        params.put("gameId","10");
        long timestamp = new Date().getTime(); //13位的时间戳
        params.put("_",Long.toString(timestamp));

        HttpHeaders headers = genHttpHeader.gen("toServerList"); //生成请求头
        HttpEntity<String> requestEntity = new HttpEntity<>(null, headers);//将header放入一个请求
        ResponseEntity<String> responseEntity=restTemplate.exchange(woniuJishiUrl + "loadServerList.do?gameId={gameId}&_={_}", HttpMethod.GET,requestEntity,String.class,params);
        String content = responseEntity.getBody();

        JSONArray jsonArray= JSONArray.parseArray(content);

        JSONArray serverArray = JSONArray.parseArray(jsonArray.get(0).toString());

        // 结果集
        List<ServerResult> results = new ArrayList<>();

        serverArray.stream().forEach(item -> {
            JSONObject itemJ = (JSONObject)item;
            JSONArray gameServers = (JSONArray)itemJ.get("gameServers");
            gameServers.stream().forEach(server -> {
                JSONObject serverJ = (JSONObject)server;
                if(serverJ.get("parentId").equals(0)){
                    ServerResult serversResult = new ServerResult();
                    serversResult.setId(serverJ.get("id").toString());
                    serversResult.setName(serverJ.getString("areaName").toString() + "-" + serverJ.get("serverName").toString());
                    results.add(serversResult);
                }
            });
        });

        return results;
    }

    /**
     * 根据服务器Id获取服务器名称
     * @param serverId 服务器Id
     * @return
     */
    public ServerResult getServerName(String serverId){
        List<ServerResult> serverList = findServerList();
        ServerResult server = new ServerResult();
        serverList.forEach(item -> {
            if(item.getId().equals(serverId)){
                server.setId(item.getId());
                server.setName(item.getName());
                return;
            }
        });
        return server;
    }

    /**
     * 根据角色姓名与服务器Id模糊查找角色
     * @param name 角色姓名
     * @param serverId 服务器Id
     * @return
     */
    public List<RoleInfo> findRolesByName(String name, String serverId){
        List<RoleInfo> all = new ArrayList<>();
        List<RoleInfo> noticeRoleList = requestRolesByName(name, serverId,   1, "Notice");
        if(noticeRoleList != null){
            all.addAll(noticeRoleList);
        }
        List<RoleInfo> sellingRoleList = requestRolesByName(name, serverId,   1, "Selling");
        if(sellingRoleList != null){
            all.addAll(sellingRoleList);
        }
        return all;
    }

    /**
     * 根据角色Id与服务器Id查找角色
     * @param id 角色Id
     * @param serverId 服务器Id
     * @return
     */
    public List<RoleInfo> findRolesById(String id, String serverId){
        RestTemplate restTemplate=new RestTemplate(); //创建请求

        Map<String,String> params=new HashMap<>(); //创建参数表
        params.put("serverId",serverId);
        params.put("gameId","10");
        params.put("itemId",id);
        long timestamp = new Date().getTime(); //13位的时间戳
        params.put("_",Long.toString(timestamp));

        HttpHeaders headers = genHttpHeader.gen("anonymousPage"); //生成请求头
        HttpEntity<String> requestEntity = new HttpEntity<>(null, headers);//将header放入一个请求

        ResponseEntity<String> responseEntity=restTemplate.exchange(woniuJishiUrl
                        + "anonymous/getTradeItem.do?"
                        + "serverId={serverId}&gameId={gameId}&itemId={itemId}&_={_}",
                HttpMethod.GET,requestEntity,String.class,params);
        String content = responseEntity.getBody();

        JSONArray jsonArray= JSONArray.parseArray(content);

        JSONObject object = (JSONObject) jsonArray.get(0);

        JSONObject pageData = (JSONObject) object.get("pageData");

        ArrayList<RoleInfo> roles = new ArrayList<>();
        RoleInfo role = new RoleInfo();

        if (pageData == null){
            return roles;
        }

        role.setId(pageData.get("id").toString());
        role.setName(pageData.get("itemName").toString());
        role.setGrade(pageData.getString("grade").toString().length()<4?pageData.getString("gradeName").toString():pageData.getString("grade").toString());
        role.setGender(pageData.get("gender").toString());
        role.setPrice(pageData.get("price").toString());
        String neigongyanxiu = parseNeigongyanxiu(serverId, role.getId());
        if (neigongyanxiu == null){
            return roles;
        }
        role.setNeigongyanxiu(neigongyanxiu);
        role.setSchool(parseSchool(serverId,role.getId()));
        role.setServer(getServerName(serverId));

        String remarnTime = object.getString("remarnTime");
        if(remarnTime.length() == 0){
            role.setStatus(pageData.getString("status"));
        }
        else if(remarnTime.indexOf("可售剩余") > -1){
            role.setStatus("在售");
        }
        else if(remarnTime.indexOf("公示剩余") > -1){
            role.setStatus("公示期");
        }
        else {
            role.setStatus(pageData.getString("status"));
        }

        roles.add(role);
        return roles;
    }

    /**
     * 获取角色内容
     * @param serverId 服务器Id
     * @param itemId 角色Id
     * @return
     */
    public RoleContent getRoleContent(String serverId, String itemId){
        List<String> baowu = parseBaoWuBox(serverId,itemId);

        Map<String, ArrayList> equip = parseEquip(serverId, itemId);
        List<String> baowuOfBackpack = equip.get("baowuOfBackpack");
        List<String> threeSkills = equip.get("threeSkills");
        List<String> twoSkills = equip.get("twoSkills");
        List<String> wawa = equip.get("wawa");
        List<String> wawaOfBackpack = equip.get("wawaOfBackpack");

        List<Neigong> neigongs = parseNeigong(serverId,itemId);

        Map<String, ArrayList> wuxue = parseWuxue(serverId, itemId);
        List<Wuxue> gupuWuxues = wuxue.get("gupuWuxues");
        List<Wuxue> _99Wuxues = wuxue.get("_99Wuxues");

        List<Jingmai> jingmais = parseJingmai(serverId,itemId);
        List<UseCardRec> useCardRecList = parseUseCardRec(serverId,itemId);
        List<String> mounts = parseMount(serverId,itemId);

        return new RoleContent(baowu,baowuOfBackpack,threeSkills,twoSkills,wawa,wawaOfBackpack,neigongs,gupuWuxues,_99Wuxues,jingmais,useCardRecList,mounts);
    }

    /**
     * 根据姓名获取角色信息
     * @param name 角色姓名
     * @param serverId 服务器Id
     * @param pageIndex 页码
     * @param status 状态
     * @return
     */
    public List<RoleInfo> requestRolesByName(String name, String serverId, int pageIndex, String status){
        RestTemplate restTemplate=new RestTemplate(); //创建请求

        Map<String,String> params=new HashMap<>(); //创建参数表
        params.put("serverId",serverId);
        params.put("gameId","10");
        params.put("auctionFirst","1");
        params.put("filterItem","4");
        params.put("pageIndex",String.valueOf(pageIndex));
        params.put("itemName",name);
        long timestamp = new Date().getTime(); //13位的时间戳
        params.put("_",Long.toString(timestamp));

        HttpHeaders headers = genHttpHeader.gen("anonymousPage"); //生成请求头
        HttpEntity<String> requestEntity = new HttpEntity<>(null, headers);//将header放入一个请求

        ResponseEntity<String> responseEntity=restTemplate.exchange(woniuJishiUrl
                        + "anonymous/find" + status + "Goods.do?"
                        + "serverId={serverId}&gameId={gameId}&auctionFirst={auctionFirst}&filterItem={filterItem}&pageIndex={pageIndex}&itemName={itemName}&_={_}",
                HttpMethod.GET,requestEntity,String.class,params);
        String content = responseEntity.getBody();

        JSONArray jsonArray= JSONArray.parseArray(content);

        JSONObject object = (JSONObject) jsonArray.get(0);

        JSONObject pageInfo = (JSONObject) object.get("pageInfo");

        int totalCount = (int) pageInfo.get("totalCount");
        int totalPages = (int) pageInfo.get("totalPages");
        int pageId = (int) pageInfo.get("pageId");

        if(totalCount == 0){
            return null;
        }

        JSONArray pageData = (JSONArray) object.get("pageData");

        ArrayList<RoleInfo> roles = new ArrayList<>();

        pageData.stream().forEach(item -> {
            JSONObject itemJ = (JSONObject)item;
            RoleInfo role = new RoleInfo();
            role.setId(itemJ.get("id").toString());
            role.setName(itemJ.get("itemName").toString());
            role.setGrade(itemJ.getString("grade").toString().length()<4?itemJ.getString("gradeName").toString():itemJ.getString("grade").toString());
            role.setGender(itemJ.get("gender").toString());
            role.setPrice(itemJ.get("price").toString());
            role.setStatus(status.equals("Notice")?"公示期":"在售");
            role.setNeigongyanxiu(parseNeigongyanxiu(serverId,role.getId()));
            role.setSchool(parseSchool(serverId,role.getId()));
            role.setServer(getServerName(serverId));

            roles.add(role);
        });

        if(pageId<totalPages){
            List<RoleInfo> roleList = requestRolesByName(name, serverId, pageId + 1, status);
            roles.addAll(roleList);
        }

        return roles;
    }

    /**
     * 获取角色内功研修
     * @param serverId 服务器Id
     * @param itemId 角色Id
     * @return
     */
    public String parseNeigongyanxiu(String serverId, String itemId){
        RestTemplate restTemplate=new RestTemplate(); //创建请求

        Map<String,String> params=new HashMap<>(); //创建参数表
        params.put("serverId",serverId);
        params.put("itemId",itemId);
        params.put("type","OtherProp");
        long timestamp = new Date().getTime(); //13位的时间戳
        params.put("_",Long.toString(timestamp));

        HttpHeaders headers = genHttpHeader.gen("roles"); //生成请求头
        HttpEntity<String> requestEntity = new HttpEntity<>(null, headers);//将header放入一个请求

        ResponseEntity<String> responseEntity=restTemplate.exchange(woniuJishiUrl
                        + "roleMsgInfo.do?"
                        + "serverId={serverId}&itemId={itemId}&type={type}&_={_}",
                HttpMethod.GET,requestEntity,String.class,params);
        String content = responseEntity.getBody();

        JSONArray jsonArray= JSONArray.parseArray(content);

        JSONObject object = (JSONObject) jsonArray.get(0);

        String msg = object.get("msg").toString();
        if(!StringUtils.hasText(msg)){
            return null;
        }

        int beg = msg.indexOf("内功研修") + 6;
        int end = msg.indexOf("武学") - 4;
        String neigongyanxiu = msg.substring(beg,end);

        if (neigongyanxiu.length()>4) {
            neigongyanxiu = neigongyanxiu.substring(0,neigongyanxiu.length()-4) + "亿" + neigongyanxiu.substring(neigongyanxiu.length()-4,neigongyanxiu.length()) + "万";
        }
        else {
            neigongyanxiu = neigongyanxiu + "万";
        }

        return neigongyanxiu;
    }

    /**
     * 获取角色宗派
     * @param serverId 服务器Id
     * @param itemId 角色Id
     * @return
     */
    public String parseSchool(String serverId, String itemId){
        RestTemplate restTemplate=new RestTemplate(); //创建请求

        Map<String,String> params=new HashMap<>(); //创建参数表
        params.put("serverId",serverId);
        params.put("itemId",itemId);
        params.put("type","NeiGongContainer");
        long timestamp = new Date().getTime(); //13位的时间戳
        params.put("_",Long.toString(timestamp));

        HttpHeaders headers = genHttpHeader.gen("roles"); //生成请求头
        HttpEntity<String> requestEntity = new HttpEntity<>(null, headers);//将header放入一个请求

        ResponseEntity<String> responseEntity=restTemplate.exchange(woniuJishiUrl
                        + "roleMsgInfo.do?"
                        + "serverId={serverId}&itemId={itemId}&type={type}&_={_}",
                HttpMethod.GET,requestEntity,String.class,params);
        String content = responseEntity.getBody();

        JSONArray jsonArray= JSONArray.parseArray(content);

        JSONObject object = (JSONObject) jsonArray.get(0);

        JSONArray msg = JSONArray.parseArray(object.get("msg").toString());

        AtomicReference<String> school = new AtomicReference<>("");

        msg.stream().forEach(item -> {
            JSONObject itemJ = (JSONObject)item;
            String neigongName = itemJ.getString("name");

            if(neigongName.equals("淇奥诀") || neigongName.equals("风起诀") || neigongName.equals("昆仑引") || neigongName.equals("昆仑会意功")){
                school.set("昆仑");
                return;
            }
            else if(neigongName.equals("冰肌玉骨功") || neigongName.equals("大乘涅磐功") || neigongName.equals("心莲无量功") || neigongName.equals("峨眉会意功")){
                school.set("峨眉");
                return;
            }
            else if(neigongName.equals("魔相诀") || neigongName.equals("拍影功") || neigongName.equals("噬月神鉴") || neigongName.equals("极乐会意功")){
                school.set("极乐");
                return;
            }
            else if(neigongName.equals("酒雨神功") || neigongName.equals("伏龙诀") || neigongName.equals("一气海纳功") || neigongName.equals("丐帮会意功")){
                school.set("丐帮");
                return;
            }
            else if(neigongName.equals("纯阳无极功") || neigongName.equals("齐天真罡") || neigongName.equals("太极神功") || neigongName.equals("武当会意功")){
                school.set("武当");
                return;
            }
            else if(neigongName.equals("葬花玄功录") || neigongName.equals("九天凤舞仙诀") || neigongName.equals("溪月花香集") || neigongName.equals("君子会意功")){
                school.set("君子");
                return;
            }
            else if(neigongName.equals("太素玄阴经") || neigongName.equals("断脉逆心功") || neigongName.equals("九转天邪经") || neigongName.equals("唐门会意功")){
                school.set("唐门");
                return;
            }
            else if(neigongName.equals("混天宝纲") || neigongName.equals("地狱换魂经") || neigongName.equals("修罗武经") || neigongName.equals("锦衣会意功")){
                school.set("锦衣");
                return;
            }
            else if(neigongName.equals("旃檀心经") || neigongName.equals("洗髓经") || neigongName.equals("灭相禅功") || neigongName.equals("少林会意功")){
                school.set("少林");
                return;
            }
            else if(neigongName.equals("燎原神功") || neigongName.equals("明王宝策") || neigongName.equals("移天焚海诀") || neigongName.equals("明教会意功")){
                school.set("明教");
                return;
            }
            else if(neigongName.equals("梅影抄") || neigongName.equals("云天谱") || neigongName.equals("雷音神典") || neigongName.equals("天山会意功")){
                school.set("天山");
                return;
            }
        });

        if(school.toString().length() == 0){
            school.set("未知");
        }

        return school.toString();
    }

    /**
     * 获取角色内功
     * @param serverId 服务器Id
     * @param itemId 角色Id
     * @return
     */
    public List<Neigong> parseNeigong(String serverId, String itemId){
        RestTemplate restTemplate=new RestTemplate(); //创建请求

        Map<String,String> params=new HashMap<>(); //创建参数表
        params.put("serverId",serverId);
        params.put("itemId",itemId);
        params.put("type","NeiGongContainer");
        long timestamp = new Date().getTime(); //13位的时间戳
        params.put("_",Long.toString(timestamp));

        HttpHeaders headers = genHttpHeader.gen("roles"); //生成请求头
        HttpEntity<String> requestEntity = new HttpEntity<>(null, headers);//将header放入一个请求

        ResponseEntity<String> responseEntity=restTemplate.exchange(woniuJishiUrl
                        + "roleMsgInfo.do?"
                        + "serverId={serverId}&itemId={itemId}&type={type}&_={_}",
                HttpMethod.GET,requestEntity,String.class,params);
        String content = responseEntity.getBody();

        JSONArray jsonArray= JSONArray.parseArray(content);

        JSONObject object = (JSONObject) jsonArray.get(0);

        JSONArray msg = JSONArray.parseArray(object.get("msg").toString());

        ArrayList<Neigong> neigongs = new ArrayList<>();

        msg.stream().forEach(item -> {
            JSONObject itemJ = (JSONObject) item;
            String name = itemJ.getString("name");
            String info = itemJ.getString("dataInfo");
            String type = info.substring(info.indexOf("归属：")+3, info.indexOf("功力：")-4);
            String level = info.substring(info.indexOf("功力：")+3, info.indexOf("<br><font"));
            Neigong neigong = new Neigong(name, type, level);
            neigongs.add(neigong);
        });

        // 按内功类型排序
        neigongs.sort(Comparator.comparing(item->{
            return item.type;
        }));

        return neigongs;
    }

    /**
     * 获取并解析角色的武学
     * @param serverId 服务器Id
     * @param itemId 角色Id
     * @return
     */
    public Map<String, ArrayList> parseWuxue(String serverId, String itemId){
        RestTemplate restTemplate=new RestTemplate(); //创建请求

        Map<String,String> params=new HashMap<>(); //创建参数表
        params.put("serverId",serverId);
        params.put("itemId",itemId);
        params.put("type","SkillContainer");
        long timestamp = new Date().getTime(); //13位的时间戳
        params.put("_",Long.toString(timestamp));

        HttpHeaders headers = genHttpHeader.gen("roles"); //生成请求头
        HttpEntity<String> requestEntity = new HttpEntity<>(null, headers);//将header放入一个请求

        ResponseEntity<String> responseEntity=restTemplate.exchange(woniuJishiUrl
                        + "roleMsgInfo.do?"
                        + "serverId={serverId}&itemId={itemId}&type={type}&_={_}",
                HttpMethod.GET,requestEntity,String.class,params);
        String content = responseEntity.getBody();

        JSONArray jsonArray= JSONArray.parseArray(content);

        JSONObject object = (JSONObject) jsonArray.get(0);

        JSONArray msg = JSONArray.parseArray(object.get("msg").toString());

        ArrayList<Wuxue> gupuWuxues = new ArrayList<>();
        ArrayList<Wuxue> _99Wuxues = new ArrayList<>();

        msg.stream().forEach(item -> {
            JSONObject itemJ = (JSONObject) item;
            String type = itemJ.getString("type");
            if(isGupuWuxue(type)){
                String name = itemJ.getString("name");
                String info = itemJ.getString("dataInfo");
                String level = info.substring(info.indexOf("功力：")+3, info.indexOf("层）<br>")) + "层）";
                Wuxue gupuWuxue = new Wuxue(name,type,level);
                gupuWuxues.add(gupuWuxue);
            }
            else if(is99Wuxue(type)){
                String name = itemJ.getString("name");
                String info = itemJ.getString("dataInfo");
                String level = info.substring(info.indexOf("功力：")+3, info.indexOf("层）<br>")) + "层）";
                Wuxue _99Wuxue = new Wuxue(name,type,level);
                _99Wuxues.add(_99Wuxue);
            }
            else if(type.equals("江湖散招/江湖散手") && itemJ.getString("name").equals("神行百变")){
                String name = itemJ.getString("name");
                String info = itemJ.getString("dataInfo");
                String level = info.substring(info.indexOf("功力：")+3, info.indexOf("层）<br>")) + "层）";
                Wuxue shenxing = new Wuxue(name,"徒手套路/野球拳（"+type+"）",level);
                gupuWuxues.add(shenxing);
            }
        });

        gupuWuxues.sort(Comparator.comparing(item->{
            return item.type;
        }));

        _99Wuxues.sort(Comparator.comparing(item->{
            return item.type;
        }));

        Map<String, ArrayList> result = new HashMap<>(); //创建结果集
        result.put("gupuWuxues",gupuWuxues);
        result.put("_99Wuxues",_99Wuxues);

        return result;
    }

    /**
     * 获取并解析角色的经脉
     * @param serverId 服务器Id
     * @param itemId 角色Id
     * @return
     */
    public List<Jingmai> parseJingmai(String serverId, String itemId){
        RestTemplate restTemplate=new RestTemplate(); //创建请求

        Map<String,String> params=new HashMap<>(); //创建参数表
        params.put("serverId",serverId);
        params.put("itemId",itemId);
        params.put("type","JingMaiContainer");
        long timestamp = new Date().getTime(); //13位的时间戳
        params.put("_",Long.toString(timestamp));

        HttpHeaders headers = genHttpHeader.gen("roles"); //生成请求头
        HttpEntity<String> requestEntity = new HttpEntity<>(null, headers);//将header放入一个请求

        ResponseEntity<String> responseEntity=restTemplate.exchange(woniuJishiUrl
                        + "roleMsgInfo.do?"
                        + "serverId={serverId}&itemId={itemId}&type={type}&_={_}",
                HttpMethod.GET,requestEntity,String.class,params);
        String content = responseEntity.getBody();

        JSONArray jsonArray= JSONArray.parseArray(content);

        JSONObject object = (JSONObject) jsonArray.get(0);

        JSONArray msg = JSONArray.parseArray(object.get("msg").toString());

        ArrayList<Jingmai> jingmais = new ArrayList<>();

        msg.stream().forEach(item -> {
            JSONObject itemJ = (JSONObject) item;
            String name = itemJ.getString("name");
            String info = itemJ.getString("dataInfo");
            String level = info.substring(info.indexOf("已修炼至：")+5, info.indexOf("周天")) + "周天";
            String school = "";
            if(info.contains("主修")){
                school = info.substring(info.indexOf("[推荐")+3, info.indexOf("主修"));
            }
            String qizhen = "";
            if(info.contains("奇珍效果")){
                qizhen = info.substring(info.indexOf("奇珍效果：</font><br>")+16, info.indexOf("</div></div></div>"));
                qizhen = qizhen.replace("<br>"," ");
            }
            Jingmai jingmai = new Jingmai(name,level,school,qizhen);
            jingmais.add(jingmai);
        });

        jingmais.sort(Comparator.comparing(item->{
            return item.name;
        }));

        return jingmais;
    }

    /**
     * 获取并解析角色的穿戴宝物
     * @param serverId
     * @param itemId
     * @return
     */
    public List<String> parseBaoWuBox(String serverId, String itemId){
        RestTemplate restTemplate=new RestTemplate(); //创建请求

        Map<String,String> params=new HashMap<>(); //创建参数表
        params.put("serverId",serverId);
        params.put("itemId",itemId);
        params.put("type","BaoWuBox");
        long timestamp = new Date().getTime(); //13位的时间戳
        params.put("_",Long.toString(timestamp));

        HttpHeaders headers = genHttpHeader.gen("roles"); //生成请求头
        HttpEntity<String> requestEntity = new HttpEntity<>(null, headers);//将header放入一个请求

        ResponseEntity<String> responseEntity=restTemplate.exchange(woniuJishiUrl
                        + "roleMsgInfo.do?"
                        + "serverId={serverId}&itemId={itemId}&type={type}&_={_}",
                HttpMethod.GET,requestEntity,String.class,params);
        String content = responseEntity.getBody();

        JSONArray jsonArray= JSONArray.parseArray(content);

        JSONObject object = (JSONObject) jsonArray.get(0);

        JSONArray msg = JSONArray.parseArray(object.get("msg").toString());

        ArrayList baoWuBox = new ArrayList<>();

        msg.stream().forEach(item -> {
            JSONObject itemJ = (JSONObject) item;
            String info = itemJ.getString("dataInfo");
            baoWuBox.add(info);
        });

        return baoWuBox;
    }

    /**
     * 获取并解析角色的装备
     * @param serverId 服务器Id
     * @param itemId 角色Id
     * @return
     */
    public Map<String, ArrayList> parseEquip(String serverId, String itemId){
        ArrayList wawa = new ArrayList<>();
        ArrayList wawaOfBackpack = new ArrayList<>();

        ArrayList baowuOfBackpack = new ArrayList<>();

        ArrayList threeSkills = new ArrayList<>();
        ArrayList twoSkills = new ArrayList<>();

        RestTemplate restTemplate=new RestTemplate(); //创建请求

        Map<String,String> params=new HashMap<>(); //创建参数表
        params.put("serverId",serverId);
        params.put("itemId",itemId);
        params.put("type","EquipBox");
        long timestamp = new Date().getTime(); //13位的时间戳
        params.put("_",Long.toString(timestamp));

        HttpHeaders headers = genHttpHeader.gen("roles"); //生成请求头
        HttpEntity<String> requestEntity = new HttpEntity<>(null, headers);//将header放入一个请求

        ResponseEntity<String> responseEntity=restTemplate.exchange(woniuJishiUrl
                        + "roleMsgInfo.do?"
                        + "serverId={serverId}&itemId={itemId}&type={type}&_={_}",
                HttpMethod.GET,requestEntity,String.class,params);
        String content = responseEntity.getBody();

        JSONArray jsonArray= JSONArray.parseArray(content);

        JSONObject object = (JSONObject) jsonArray.get(0);

        JSONArray msg = JSONArray.parseArray(object.get("msg").toString());

        msg.stream().forEach(item -> {
            JSONObject itemJ = (JSONObject) item;
            String info = itemJ.getString("dataInfo");
            String itemType = itemJ.getString("itemType");

            // 娃娃
            if(itemType.equals("193")){
                wawa.add(info);
            }
            // 背包宝物
            else if(itemType.equals("146")){
                baowuOfBackpack.add(info);
            }
            // 装备武器
            else {
                SAXReader reader = new SAXReader();
                Document document = null;
                try {
                    try {
                        document = reader.read(new ByteArrayInputStream(info.replace("<br>","").getBytes("UTF-8")));
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                        return;
                    }
                } catch (DocumentException e) {
                    e.printStackTrace();
                    return;
                }
                List<Node> elementList = document.selectNodes("//font[@color1=\"#eb6100\"]");

                if(elementList.size() >= 6){
                    if(elementList.get(1).getText().trim().equals(elementList.get(3).getText().trim()) && elementList.get(3).getText().trim().equals(elementList.get(5).getText().trim())){
                        threeSkills.add(info);
                    }
                    else{
                        if(elementList.get(1).getText().trim().equals(elementList.get(3).getText().trim()) || elementList.get(1).getText().trim().equals(elementList.get(5).getText().trim()) || elementList.get(3).getText().trim().equals(elementList.get(5).getText().trim())){
                            twoSkills.add(info);
                        }
                    }
                }
            }
        });

        RestTemplate restTemplate2 = new RestTemplate(); //创建请求

        Map<String,String> params2 = new HashMap<>(); //创建参数表
        params2.put("serverId",serverId);
        params2.put("itemId",itemId);
        params2.put("type","EquipToolBox");
        long timestamp2 = new Date().getTime(); //13位的时间戳
        params2.put("_",Long.toString(timestamp2));

        HttpHeaders headers2 = genHttpHeader.gen("roles"); //生成请求头
        HttpEntity<String> requestEntity2 = new HttpEntity<>(null, headers2);//将header放入一个请求

        ResponseEntity<String> responseEntity2 = restTemplate2.exchange(woniuJishiUrl
                        + "roleMsgInfo.do?"
                        + "serverId={serverId}&itemId={itemId}&type={type}&_={_}",
                HttpMethod.GET,requestEntity2,String.class,params2);
        String content2 = responseEntity2.getBody();

        JSONArray jsonArray2= JSONArray.parseArray(content2);

        JSONObject object2 = (JSONObject) jsonArray2.get(0);

        JSONArray msg2 = JSONArray.parseArray(object2.get("msg").toString());

        msg2.stream().forEach(item -> {
            JSONObject itemJ = (JSONObject) item;
            String info = itemJ.getString("dataInfo");
            String itemType = itemJ.getString("itemType");

            // 娃娃
            if(itemType.equals("193")){
                wawaOfBackpack.add(info);
            }
            // 背包宝物
            else if(itemType.equals("146")){
                baowuOfBackpack.add(info);
            }
            // 装备武器
            else{
                SAXReader reader = new SAXReader();
                Document document = null;
                try {
                    try {
                        document = reader.read(new ByteArrayInputStream(info.replace("<br>","").getBytes("UTF-8")));
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                        return;
                    }
                } catch (DocumentException e) {
                    e.printStackTrace();
                    return;
                }
                List<Node> elementList = document.selectNodes("//font[@color1=\"#eb6100\"]");

                if(elementList.size() >= 6){
                    if(elementList.get(1).getText().trim().equals(elementList.get(3).getText().trim()) && elementList.get(3).getText().trim().equals(elementList.get(5).getText().trim())){
                        threeSkills.add(info);
                    }
                    else{
                        if(elementList.get(1).getText().trim().equals(elementList.get(3).getText().trim()) || elementList.get(1).getText().trim().equals(elementList.get(5).getText().trim()) || elementList.get(3).getText().trim().equals(elementList.get(5).getText().trim())){
                            twoSkills.add(info);
                        }
                    }
                }
            }
        });

        Map<String, ArrayList> result = new HashMap<>(); //创建结果集
        result.put("threeSkills",threeSkills);
        result.put("twoSkills",twoSkills);
        result.put("wawa",wawa);
        result.put("wawaOfBackpack",wawaOfBackpack);
        result.put("baowuOfBackpack",baowuOfBackpack);

        return result;
    }

    /**
     * 获取并解析角色的坐骑
     * @param serverId 服务器Id
     * @param itemId 角色Id
     * @return
     */
    public List<String> parseMount(String serverId, String itemId){
        RestTemplate restTemplate=new RestTemplate(); //创建请求

        Map<String,String> params=new HashMap<>(); //创建参数表
        params.put("serverId",serverId);
        params.put("itemId",itemId);
        params.put("type","ToolBox");
        long timestamp = new Date().getTime(); //13位的时间戳
        params.put("_",Long.toString(timestamp));

        HttpHeaders headers = genHttpHeader.gen("roles"); //生成请求头
        HttpEntity<String> requestEntity = new HttpEntity<>(null, headers);//将header放入一个请求

        ResponseEntity<String> responseEntity=restTemplate.exchange(woniuJishiUrl
                        + "roleMsgInfo.do?"
                        + "serverId={serverId}&itemId={itemId}&type={type}&_={_}",
                HttpMethod.GET,requestEntity,String.class,params);
        String content = responseEntity.getBody();

        JSONArray jsonArray= JSONArray.parseArray(content);

        JSONObject object = (JSONObject) jsonArray.get(0);

        JSONArray msg = JSONArray.parseArray(object.get("msg").toString());

        List mounts = new ArrayList<>();

        msg.stream().forEach(item -> {
            JSONObject itemJ = (JSONObject) item;
            String info = itemJ.getString("dataInfo");
            String itemType = itemJ.getString("itemType");
            String name = itemJ.getString("name");
            if(itemType.equals("200") && !name.startsWith("马哨")){
                mounts.add(info);
            }
        });

        return mounts;
    }

    /**
     * 获取并解析角色的风物志
     * @param serverId
     * @param itemId
     * @return
     */
    public List<UseCardRec> parseUseCardRec(String serverId, String itemId) {
        RestTemplate restTemplate = new RestTemplate(); //创建请求

        Map<String, String> params = new HashMap<>(); //创建参数表
        params.put("serverId", serverId);
        params.put("itemId", itemId);
        params.put("type", "UseCardRec");
        long timestamp = new Date().getTime(); //13位的时间戳
        params.put("_", Long.toString(timestamp));

        HttpHeaders headers = genHttpHeader.gen("roles"); //生成请求头
        HttpEntity<String> requestEntity = new HttpEntity<>(null, headers);//将header放入一个请求

        ResponseEntity<String> responseEntity = restTemplate.exchange(woniuJishiUrl
                        + "roleMsgInfo.do?"
                        + "serverId={serverId}&itemId={itemId}&type={type}&_={_}",
                HttpMethod.GET, requestEntity, String.class, params);
        String content = responseEntity.getBody();

        JSONArray jsonArray = JSONArray.parseArray(content);

        JSONObject object = (JSONObject) jsonArray.get(0);

        JSONArray msg = JSONArray.parseArray(object.get("msg").toString());

        ArrayList<UseCardRec> useCardRecList = new ArrayList<>();

        msg.stream().forEach(item -> {
            JSONObject itemJ = (JSONObject) item;
            String info = itemJ.getString("dataInfo");
            String name = itemJ.getString("name");
            String photo = itemJ.getString("photo");
            String quality = info.substring(info.indexOf("品质:")+3,info.indexOf("品质:")+5);
            String type = "";

            SAXReader reader = new SAXReader();
            Document document = null;
            try {
                document = reader.read(new ByteArrayInputStream(info.replace("<br>","").getBytes("UTF-8")));
            } catch (DocumentException e) {
                e.printStackTrace();
                return;
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
                return;
            }
            Node node = document.selectSingleNode("//font[@color=\"#FFFFFF\"]");
            if(node!=null){
                type = node.getText();
            }
            else {
                type = info.substring(info.indexOf("类型:")+3,info.indexOf("品质:")-4);
            }

            UseCardRec useCardRec = new UseCardRec(name,type,quality,photo);
            useCardRecList.add(useCardRec);
        });

        useCardRecList.sort(Comparator.comparing(item->{
            return item.type;
        }));

        return useCardRecList;
    }

    /**
     * 根据宝物查询
     * @param bwa1
     * @param bwa2
     * @param bwa3
     * @param bwa4
     * @param bwa5
     * @param wuxue
     * @param is750
     * @param serverId
     * @return
     */
    public List findByTreasure(String bwa1, String bwa2, String bwa3, String bwa4, String bwa5, String wuxue, String is750, String serverId){
        String sql = "";
        String serversql = "";

        if(!serverId.equals("")){
            serversql = "WHERE role.server_id = '" + serverId + "'";
        }

        // 无参情况
        if(bwa1.equals("") && bwa2.equals("") && bwa3.equals("") && bwa4.equals("") && bwa5.equals("") && wuxue.equals("") && is750.equals("false")){
            sql = "SELECT * FROM role " + serversql + " ORDER BY role.price";
        }
        // 有参
        else{
            String paramSql = "";
            String paramCountSql = "";
            String wuxueSql = wuxue.equals("") ? "" : "AND treasure.wuxue='"+wuxue+"' ";
            String is750sql = is750.equals("false") ? "" : "AND treasure.is750=TRUE ";
            String clause1 = "";
            String clause2 = "";

            // 只根据宝物表查询，不涉及宝物属性表的情况
            if(bwa1.equals("") && bwa2.equals("") && bwa3.equals("") && bwa4.equals("") && bwa5.equals("")){
                clause1 = "FROM treasure WHERE ";
                //wuxue 和 is750 一定至少有一个非空
                if(wuxue.equals("")){  //wuxue空 is750非空
                    is750sql = "treasure.is750=TRUE ";
                }
                else{ //wuxue 非空
                    wuxueSql= "treasure.wuxue='"+wuxue+"' ";
                }
                clause2 = "";
            }
            else{
                clause1 = "FROM treasure,treasure_prop WHERE treasure.id=treasure_prop.treasure_id ";
                clause2 = "GROUP BY treasure.id HAVING COUNT(treasure.id)>";
            }

            if(!bwa1.equals("")) {
                paramSql = "AND (treasure_prop.prop LIKE '" + bwa1 + "%'";
                paramCountSql = "0";
                if(!bwa2.equals("")){
                    paramSql += " OR treasure_prop.prop LIKE '" + bwa2 + "%'";
                    paramCountSql = "1";
                    if(!bwa3.equals("")){
                        paramSql += " OR treasure_prop.prop LIKE '" + bwa3 + "%'";
                        paramCountSql = "2";
                        if(!bwa4.equals("")){
                            paramSql += " OR treasure_prop.prop LIKE '" + bwa4 + "%'";
                            paramCountSql = "3";
                            if(!bwa5.equals("")){
                                paramSql += " OR treasure_prop.prop LIKE '" + bwa5 + "%'";
                                paramCountSql = "4";
                            }
                        }
                    }
                }
                paramSql += ") ";
            }

            if(!serverId.equals("")){
                serversql = "AND role.server_id = '" + serverId + "' ";
            }
            else {
                serversql = "";
            }

            // 组合sql语句
            sql = "SELECT role.id,role.grade,role.server_id,role.name,role.gender,role.school,role.neigongyanxiu,role.price,role.status,role.server_name " +
                    "FROM (SELECT treasure.`role_id` " +
                    clause1 + wuxueSql + is750sql + paramSql +
                    clause2 + paramCountSql + ") AS tmp ,role " +
                    "WHERE tmp.role_id = role.id " + serversql +
                    "GROUP BY tmp.role_id " +
                    "HAVING COUNT(tmp.role_id)>4 " +
                    "ORDER BY role.price";
        }
        System.out.println(sql);
        List response = jdbcTemplate.queryForList(sql); //执行 sql查询
        return response;
    }

    /**
     * 根据三技能查询
     * @param wx1
     * @param ts1
     * @param wx2
     * @param ts2
     * @param wx3
     * @param ts3
     * @param wx4
     * @param ts4
     * @param wx5
     * @param ts5
     * @param serverId
     * @return
     */
    public List findByThreeSkills(String wx1, String ts1, String wx2, String ts2, String wx3, String ts3, String wx4, String ts4, String wx5, String ts5, String serverId){
        String sql = "";
        String clause = "";
        String count = "";
        String serversql = "";

        if(!serverId.equals("")){
            serversql = "WHERE role.server_id = '" + serverId + "'";
        }

        // 无参情况
        if(wx1.equals("") && wx2.equals("") && wx3.equals("") && wx4.equals("") && wx5.equals("")){
            sql = "SELECT * FROM role " + serversql + " ORDER BY role.price";
        }
        // 有参
        else{
            if(!wx1.equals("")) {
                clause += "((threeskills.`wuxue`='" + wx1 + "' AND threeskills.`skill`='" + ts1 + "')";
                count = "0";
                if(!wx2.equals("")){
                    clause += " OR (threeskills.`wuxue`='" + wx2 + "' AND threeskills.`skill`='" + ts2 + "')";
                    count = "1";
                    if(!wx3.equals("")){
                        clause += " OR (threeskills.`wuxue`='" + wx3 + "' AND threeskills.`skill`='" + ts3 + "')";
                        count = "2";
                        if(!wx4.equals("")){
                            clause += " OR (threeskills.`wuxue`='" + wx4 + "' AND threeskills.`skill`='" + ts4 + "')";
                            count = "3";
                            if(!wx5.equals("")) {
                                clause += " OR (threeskills.`wuxue`='" + wx5 + "' AND threeskills.`skill`='" + ts5 + "')";
                                count = "4";
                            }
                        }
                    }
                }
                clause += ") ";
            }

            if(!serverId.equals("")){
                serversql = "AND role.server_id = '" + serverId + "' ";
            }
            else {
                serversql = "";
            }

            sql = "SELECT role.id,role.grade,role.server_id,role.name,role.gender,role.school,role.neigongyanxiu,role.price,role.status,role.server_name " +
                    "FROM role,threeskills " +
                    "WHERE role.`id`=threeskills.`role_id` AND " +
                    clause + serversql +
                    "GROUP BY role.id " +
                    "HAVING COUNT(role.id)>" + count +
                    " ORDER BY role.price";

        }
        System.out.println(sql);
        List response = jdbcTemplate.queryForList(sql);

        return response;
    }

    /**
     * 根据风物志查询
     * @param skin1
     * @param skin2
     * @param skin3
     * @param skin4
     * @param skin5
     * @param serverId
     * @return
     */
    public List findBySkin(String skin1, String skin2, String skin3, String skin4, String skin5, String serverId){
        String sql = "";
        String clause = "";
        String count = "";
        String serversql = "";

        if(!serverId.equals("")){
            serversql = "WHERE role.server_id = '" + serverId + "'";
        }

        // 无参情况
        if(skin1.equals("") && skin2.equals("") && skin3.equals("") && skin4.equals("") && skin5.equals("")){
            sql = "SELECT * FROM role " + serversql + " ORDER BY role.price";
        }
        // 有参
        else {
            if (!skin1.equals("")) {
                clause += "((skin.name ='" + skin1 + "'" + " OR skin.name LIKE'" + skin1 + "·%')";
                count = "0";
                if (!skin2.equals("")) {
                    clause += " OR (skin.name ='" + skin2 + "'" + " OR skin.name LIKE'" + skin2 + "·%')";
                    count = "1";
                    if (!skin3.equals("")) {
                        clause += " OR (skin.name ='" + skin3 + "'" + " OR skin.name LIKE'" + skin3 + "·%')";
                        count = "2";
                        if (!skin4.equals("")) {
                            clause += " OR (skin.name ='" + skin4 + "'" + " OR skin.name LIKE'" + skin4 + "·%')";
                            count = "3";
                            if (!skin5.equals("")) {
                                clause += " OR (skin.name ='" + skin5 + "'" + " OR skin.name LIKE'" + skin5 + "·%')";
                                count = "4";
                            }
                        }
                    }
                }
                clause += ") ";
            }

            if(!serverId.equals("")){
                serversql = "AND role.server_id = '" + serverId + "' ";
            }
            else {
                serversql = "";
            }

            sql = "SELECT role.id,role.grade,role.server_id,role.name,role.gender,role.school,role.neigongyanxiu,role.price,role.status,role.server_name " +
                    "FROM role,skin " +
                    "WHERE role.`id`=skin.`role_id` AND " +
                    clause + serversql +
                    "GROUP BY role.id " +
                    "HAVING COUNT(role.id)>" + count +
                    " ORDER BY role.price";
        }
        System.out.println(sql);
        List response = jdbcTemplate.queryForList(sql);

        return response;
    }

    /**
     * 获取爬虫数据更新时间
     * @return
     */
    public Map getUpdateTime(){
        Map response = new HashMap();

        try {
            File txtFile = new File(endTxt);
            InputStreamReader reader = new InputStreamReader(new FileInputStream(txtFile)); // 建立一个输入流对象reader
            BufferedReader br = new BufferedReader(reader); // 建立一个对象，它把文件内容转成计算机能读懂的语言

            String endTime = br.readLine(); // 第一行是爬虫结束时间
            String endSignal = br.readLine(); // 第二行是爬虫结束信号

            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime parseTime = LocalDateTime.parse(endTime, dtf);

            LocalDateTime nowTime = LocalDateTime.now(); // 当前时间

            if(nowTime.isAfter(parseTime) && endSignal.equals("finished")){
                response.put("isMaintenance",false);
            }
            else {
                response.put("isMaintenance",true);
            }

            response.put("time",endTime);
            response.put("affiche", "2021-09-09 : 新增根据风物志修竹伯玉、冰晶莲华、如云之乘的搜索。");

            br.close();
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return response;
    }
}
