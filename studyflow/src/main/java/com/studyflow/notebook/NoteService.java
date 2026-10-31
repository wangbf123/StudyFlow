package com.studyflow.notebook;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.studyflow.agent.AgentJson;
import com.studyflow.common.BusinessException;
import com.studyflow.kb.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static com.studyflow.notebook.NoteTypes.*;

@Service
@RequiredArgsConstructor
public class NoteService {
    private final JdbcTemplate jdbc;
    private final KnowledgeBaseService bases;
    private final WikiLinks wiki;
    private final AgentJson json;
    private final java.util.Optional<Neo4jGraphStore> graphStore;

    public List<Long> scope(Long user,List<Long> ids) {
        if(ids==null||ids.isEmpty()||ids.size()>32)throw new BusinessException(400,"请选择 1 至 32 个知识库");
        var distinct=ids.stream().distinct().sorted().toList();
        for(Long id:distinct)bases.getOwnedActive(user,id);return distinct;
    }
    private void ownerLock(Long user) {jdbc.queryForObject("SELECT id FROM sys_user WHERE id=? FOR UPDATE",Long.class,user);}
    private String placeholders(List<?> ids) {return String.join(",",Collections.nCopies(ids.size(),"?"));}
    private List<Object> params(Long user,List<Long> ids){var args=new ArrayList<Object>();args.add(user);args.addAll(ids);return args;}

    @Transactional
    public Note save(Long user,Long id,Write request) {return saveInternal(user,id,request,null);}
    @Transactional
    public Note fromArtifact(Long user,Long kb,String title,String markdown,Long runId) {
        ownerLock(user);bases.getOwnedActive(user,kb);
        var existing=jdbc.query("SELECT id FROM knowledge_note WHERE user_id=? AND knowledge_base_id=? AND origin_run_id=? AND status='ACTIVE'",(rs,n)->rs.getLong(1),user,kb,runId);
        if(!existing.isEmpty())return get(user,existing.getFirst());
        String candidate=title;int suffix=2;
        while(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_note WHERE user_id=? AND knowledge_base_id=? AND title_key=? AND status='ACTIVE'",Integer.class,user,kb,WikiLinks.key(candidate))>0) {
            candidate=AgentJson.clip(title,140)+" · "+suffix++;
            if(suffix>100)throw new BusinessException(409,"同名报告较多，请指定新的标题");
        }
        return saveInternal(user,null,new Write(kb,candidate,markdown,0,List.of("agent"),json.tree("{}"),"REPORT"),runId);
    }
    private Note saveInternal(Long user,Long id,Write request,Long origin) {
        ownerLock(user);bases.getOwnedActive(user,request.knowledgeBaseId());
        String title=request.title().strip();if(title.isEmpty())throw new BusinessException(400,"笔记标题不能为空");
        JsonNode properties=request.properties()==null?json.tree("{}"):request.properties();
        if(!properties.isObject()||properties.size()>40||json.write(properties).length()>8000)throw new BusinessException(400,"属性须为对象且最多 8000 字符");
        var tags=request.tags()==null?List.<String>of():request.tags().stream().map(String::strip).filter(s->!s.isEmpty()).distinct().toList();
        var tokens=wiki.parse(request.body());
        Map<String,Long> prior=new HashMap<>();int version=1;Long note=id;
        if(id!=null) {
            Note old=get(user,id);
            if(!old.knowledgeBaseId().equals(request.knowledgeBaseId()))throw new BusinessException(400,"请使用链接关联其他知识库，笔记归属保持稳定");
            if(old.revision()!=request.revision())throw new BusinessException(409,"笔记已被修改，请保留草稿并加载最新版本");
            version=old.revision()+1;
            old.links().stream().filter(link->link.targetId()!=null).forEach(link->prior.put(link.targetText(),link.targetId()));
        } else if(request.revision()!=0)throw new BusinessException(409,"新笔记版本必须为 0");
        if(note==null)note=IdWorker.getId();
        try {
            if(id==null)jdbc.update("""
                    INSERT INTO knowledge_note(id,user_id,knowledge_base_id,title,title_key,body,kind,tags_json,properties_json,status,revision,origin_run_id,create_time,update_time)
                    VALUES (?,?,?,?,?,?,?,?,?,'ACTIVE',1,?,NOW(6),NOW(6))
                    """,note,user,request.knowledgeBaseId(),title,WikiLinks.key(title),request.body(),request.kind()==null?"NOTE":request.kind(),json.write(tags),json.write(properties),origin);
            else jdbc.update("UPDATE knowledge_note SET title=?,title_key=?,body=?,kind=?,tags_json=?,properties_json=?,revision=?,graph_revision=graph_revision+1,update_time=NOW(6) WHERE id=? AND user_id=? AND revision=?",
                    title,WikiLinks.key(title),request.body(),request.kind()==null?"NOTE":request.kind(),json.write(tags),json.write(properties),version,id,user,request.revision());
        } catch(DuplicateKeyException ex){throw new BusinessException(409,"此知识库已有同名笔记");}
        jdbc.update("DELETE FROM knowledge_note_link WHERE source_id=?",note);
        int ordinal=0;
        for(var token:tokens) {
            Long target=resolve(user,request.knowledgeBaseId(),token.target(),prior.get(token.target()));
            jdbc.update("INSERT INTO knowledge_note_link(source_id,ordinal,target_id,target_text,label,anchor,source_revision) VALUES (?,?,?,?,?,?,?)",
                    note,ordinal++,target,token.target(),token.label(),token.anchor(),version);
        }
        // Resolve links that were previously waiting for this same-library title, in the same owner-serialized commit.
        var unresolved=jdbc.query("""
                SELECT l.source_id,l.ordinal,l.target_text FROM knowledge_note_link l JOIN knowledge_note n ON n.id=l.source_id
                WHERE l.target_id IS NULL AND n.user_id=? AND n.knowledge_base_id=? AND n.status='ACTIVE'
                """,(rs,n)->new Object[]{rs.getLong(1),rs.getInt(2),rs.getString(3)},user,request.knowledgeBaseId());
        for(var link:unresolved)if(WikiLinks.key((String)link[2]).equals(WikiLinks.key(title))) {
            jdbc.update("UPDATE knowledge_note_link SET target_id=? WHERE source_id=? AND ordinal=?",note,link[0],link[1]);jdbc.update("UPDATE knowledge_note SET graph_revision=graph_revision+1 WHERE id=?",link[0]);outbox((Long)link[0]);
        }
        Note saved=get(user,note);
        jdbc.update("INSERT INTO knowledge_note_revision(note_id,revision,snapshot_json,create_time) VALUES (?,?,?,NOW(6))",note,version,json.write(saved));
        outbox(note);return saved;
    }
    private Long resolve(Long user,Long kb,String target,Long previous) {
        if(previous!=null && available(user,previous))return previous;
        if(target.startsWith("note:")) {
            try {long id=Long.parseLong(target.substring(5));return available(user,id)?id:null;}catch(NumberFormatException ex){return null;}
        }
        Long targetKb=kb;String title=target;
        if(target.startsWith("kb:")) {
            int slash=target.indexOf('/');if(slash<4)return null;
            try {targetKb=Long.parseLong(target.substring(3,slash));}catch(NumberFormatException ex){return null;}
            title=target.substring(slash+1);
        }
        var ids=jdbc.query("""
                SELECT n.id FROM knowledge_note n JOIN knowledge_base k ON k.id=n.knowledge_base_id
                WHERE n.user_id=? AND n.knowledge_base_id=? AND n.title_key=? AND n.status='ACTIVE' AND k.user_id=? AND k.status='ACTIVE'
                """,(rs,n)->rs.getLong(1),user,targetKb,WikiLinks.key(title),user);
        return ids.isEmpty()?null:ids.getFirst();
    }
    private boolean available(Long user,Long id) {
        return !jdbc.query("SELECT n.id FROM knowledge_note n JOIN knowledge_base k ON k.id=n.knowledge_base_id WHERE n.id=? AND n.user_id=? AND n.status='ACTIVE' AND k.user_id=? AND k.status='ACTIVE'",
                (rs,n)->rs.getLong(1),id,user,user).isEmpty();
    }
    public Note get(Long user,Long id) {
        var rows=jdbc.query("SELECT n.* FROM knowledge_note n JOIN knowledge_base k ON k.id=n.knowledge_base_id WHERE n.id=? AND n.user_id=? AND n.status='ACTIVE' AND k.user_id=? AND k.status='ACTIVE'",this::row,id,user,user);
        if(rows.isEmpty())throw new BusinessException(404,"笔记不存在或无权限");
        Note note=rows.getFirst();
        var links=jdbc.query("""
                SELECT l.*,t.title AS current_title,t.knowledge_base_id AS target_kb,
                  IF(t.status='ACTIVE' AND t.user_id=? AND k.status='ACTIVE' AND k.user_id=?,t.id,NULL) AS visible_target
                FROM knowledge_note_link l LEFT JOIN knowledge_note t ON t.id=l.target_id
                LEFT JOIN knowledge_base k ON k.id=t.knowledge_base_id WHERE l.source_id=? ORDER BY l.ordinal
                """,(rs,n)-> {
                    Long target=rs.getObject("visible_target",Long.class);
                    return new Link(rs.getInt("ordinal"),target,rs.getString("target_text"),rs.getString("label"),rs.getString("anchor"),
                            target==null?null:rs.getString("current_title"),target==null?null:rs.getLong("target_kb"));
                },user,user,id);
        return new Note(note.id(),note.knowledgeBaseId(),note.title(),note.body(),note.kind(),note.revision(),note.tags(),note.properties(),note.originRunId(),note.updateTime(),links);
    }
    public Page list(Long user,List<Long> requested,Long before,int size,String query) {
        var ids=scope(user,requested);int limit=Math.clamp(size,1,100);
        if(query!=null&&query.length()>200)throw new BusinessException(400,"搜索最多 200 字符");
        var args=params(user,ids);String where=" WHERE user_id=? AND knowledge_base_id IN ("+placeholders(ids)+") AND status='ACTIVE'";
        if(before!=null){where+=" AND id<?";args.add(before);}
        if(query!=null&&!query.isBlank()){where+=" AND (LOCATE(LOWER(?),LOWER(title))>0 OR LOCATE(LOWER(?),LOWER(body))>0)";args.add(query);args.add(query);}
        args.add(limit+1);
        var rows=jdbc.query("SELECT * FROM knowledge_note"+where+" ORDER BY id DESC LIMIT ?",(rs,n)->summary(row(rs,n)),args.toArray());
        boolean more=rows.size()>limit;var items=rows.stream().limit(limit).toList();return new Page(items,more?items.getLast().id():null,more);
    }
    public List<Summary> backlinks(Long user,Long id,List<Long> requested) {
        get(user,id);var ids=scope(user,requested);var args=params(user,ids);args.add(id);
        return jdbc.query("SELECT DISTINCT n.* FROM knowledge_note n JOIN knowledge_note_link l ON n.id=l.source_id WHERE n.user_id=? AND n.knowledge_base_id IN ("+placeholders(ids)+") AND n.status='ACTIVE' AND l.target_id=? ORDER BY n.id DESC LIMIT 100",
                (rs,n)->summary(row(rs,n)),args.toArray());
    }
    public Graph graph(Long user,List<Long> requested,Long focus) {
        var ids=scope(user,requested);var args=params(user,ids);
        var nodes=jdbc.query("SELECT id,knowledge_base_id,title,kind,revision,tags_json,update_time FROM knowledge_note WHERE user_id=? AND knowledge_base_id IN ("+placeholders(ids)+") AND status='ACTIVE' ORDER BY update_time DESC,id DESC LIMIT 301",this::summaryRow,args.toArray());
        int total=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_note WHERE user_id=? AND knowledge_base_id IN ("+placeholders(ids)+") AND status='ACTIVE'",Integer.class,args.toArray());
        var visible=new LinkedHashMap<Long,Summary>();nodes.stream().limit(300).forEach(n->visible.put(n.id(),n));
        var links=jdbc.query("""
                SELECT l.source_id,l.target_id FROM knowledge_note_link l JOIN knowledge_note s ON s.id=l.source_id
                JOIN knowledge_note t ON t.id=l.target_id
                WHERE s.user_id=? AND s.status='ACTIVE' AND t.user_id=? AND t.status='ACTIVE'
                  AND s.knowledge_base_id IN ("""+placeholders(ids)+") AND t.knowledge_base_id IN ("+placeholders(ids)+") LIMIT 3000",
                (rs,n)->new long[]{rs.getLong(1),rs.getLong(2)},linkParams(user,ids));
        String engine="MYSQL_CANONICAL";
        if(focus!=null) {
            Summary selected=getSummary(user,focus);if(!ids.contains(selected.knowledgeBaseId()))throw new BusinessException(400,"中心笔记不在选定知识库中");
            Set<Long> keep=new HashSet<>();keep.add(focus);
            // Two bounded hops; every intermediate note is within the selected scopes.
            for(int depth=0;depth<2;depth++){Set<Long> next=new HashSet<>(keep);for(var link:links){if(keep.contains(link[0]))next.add(link[1]);if(keep.contains(link[1]))next.add(link[0]);}keep=next;}
            visible.clear();var scopedNotes=new LinkedHashMap<Long,Summary>();scopedNotes.put(focus,selected);
            for(Long noteId:keep.stream().sorted().limit(100).toList())if(!noteId.equals(focus))scopedNotes.put(noteId,getSummary(user,noteId));
            visible.putAll(scopedNotes);
            if(graphStore.isPresent()) {
                try {if(graphStore.get().neighbors(user,ids,focus).equals(visible.keySet()))engine="NEO4J_VERIFIED";}
                catch(Exception ignored) { /* Canonical data remains available during projection rebuild. */ }
            }
        }
        var edges=links.stream().filter(l->visible.containsKey(l[0])&&visible.containsKey(l[1])).map(l->new GraphEdge(l[0]+":"+l[1],"n"+l[0],"n"+l[1],"WIKILINK")).distinct().toList();
        return new Graph(visible.values().stream().map(n->new GraphNode("n"+n.id(),n.title(),n.knowledgeBaseId(),n.kind(),n.tags())).toList(),edges,total,(focus==null?total>300:visible.size()>=100)||links.size()>=3000,engine);
    }
    private Object[] linkParams(Long user,List<Long> ids){var p=new ArrayList<Object>();p.add(user);p.add(user);p.addAll(ids);p.addAll(ids);return p.toArray();}
    @Transactional public void delete(Long user,Long id,int revision) {
        ownerLock(user);Note note=get(user,id);if(note.revision()!=revision)throw new BusinessException(409,"版本冲突，请刷新后重试");
        jdbc.update("UPDATE knowledge_note SET status='DELETED',revision=revision+1,graph_revision=graph_revision+1,update_time=NOW(6) WHERE id=?",id);outbox(id);
    }
    @Transactional public Note move(Long user,Long id,Long destination,int revision) {
        ownerLock(user);Note old=get(user,id);bases.getOwnedActive(user,destination);
        if(old.revision()!=revision)throw new BusinessException(409,"版本冲突，请保留草稿并刷新");
        if(old.knowledgeBaseId().equals(destination))return old;
        try {jdbc.update("UPDATE knowledge_note SET knowledge_base_id=?,revision=revision+1,graph_revision=graph_revision+1,update_time=NOW(6) WHERE id=?",destination,id);}
        catch(DuplicateKeyException ex){throw new BusinessException(409,"目标知识库已有同名笔记");}
        Note moved=get(user,id);
        jdbc.update("INSERT INTO knowledge_note_revision(note_id,revision,snapshot_json,create_time) VALUES (?,?,?,NOW(6))",id,moved.revision(),json.write(moved));
        outbox(id);return moved;
    }
    public List<Revision> revisions(Long user,Long id) {get(user,id);return jdbc.query("SELECT revision,create_time FROM knowledge_note_revision WHERE note_id=? ORDER BY revision DESC LIMIT 50",(rs,n)->new Revision(rs.getInt(1),time(rs,"create_time")),id);}
    public Note revision(Long user,Long id,int version) {
        get(user,id);var rows=jdbc.query("SELECT snapshot_json FROM knowledge_note_revision WHERE note_id=? AND revision=?",(rs,n)->json.read(rs.getString(1),Note.class),id,version);
        if(rows.isEmpty())throw new BusinessException(404,"历史版本不存在");return rows.getFirst();
    }
    private void outbox(Long id){jdbc.update("INSERT INTO knowledge_graph_outbox(id,note_id,next_attempt_at,create_time) VALUES (?,?,NOW(6),NOW(6))",IdWorker.getId(),id);}
    private Note row(ResultSet rs,int n)throws SQLException {return new Note(rs.getLong("id"),rs.getLong("knowledge_base_id"),rs.getString("title"),rs.getString("body"),rs.getString("kind"),rs.getInt("revision"),strings(rs.getString("tags_json")),json.tree(rs.getString("properties_json")),rs.getObject("origin_run_id",Long.class),time(rs,"update_time"),List.of());}
    private List<String> strings(String raw){var list=new ArrayList<String>();for(var item:json.tree(raw))list.add(item.asText());return List.copyOf(list);}
    private Summary getSummary(Long user,Long id) {
        var rows=jdbc.query("SELECT n.id,n.knowledge_base_id,n.title,n.kind,n.revision,n.tags_json,n.update_time FROM knowledge_note n JOIN knowledge_base k ON k.id=n.knowledge_base_id WHERE n.id=? AND n.user_id=? AND n.status='ACTIVE' AND k.user_id=? AND k.status='ACTIVE'",this::summaryRow,id,user,user);
        if(rows.isEmpty())throw new BusinessException(404,"笔记不存在或无权限");return rows.getFirst();
    }
    private Summary summaryRow(ResultSet rs,int n)throws SQLException{return new Summary(rs.getLong("id"),rs.getLong("knowledge_base_id"),rs.getString("title"),rs.getString("kind"),rs.getInt("revision"),strings(rs.getString("tags_json")),time(rs,"update_time"));}
    private Summary summary(Note n){return new Summary(n.id(),n.knowledgeBaseId(),n.title(),n.kind(),n.revision(),n.tags(),n.updateTime());}
    private LocalDateTime time(ResultSet rs,String field)throws SQLException{return rs.getTimestamp(field).toLocalDateTime();}
}
