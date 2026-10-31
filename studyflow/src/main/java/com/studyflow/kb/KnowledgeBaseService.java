package com.studyflow.kb;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    private void lockOwner(Long userId) {
        jdbc.queryForObject("SELECT id FROM sys_user WHERE id=? FOR UPDATE",Long.class,userId);
    }
    private void parent(Long userId,Long id,Long parentId) {
        java.util.Set<Long> visited=new java.util.HashSet<>();
        Long current=parentId;
        while(current!=null) {
            if(current.equals(id) || !visited.add(current) || visited.size()>(id==null?11:12))
                throw new BusinessException(400,"知识库层级不能形成环，最多 12 层");
            current=getOwnedActive(userId,current).getParentId();
        }
    }


    @Transactional
    public KnowledgeBaseResponse create(Long userId, KnowledgeBaseCreateRequest request) {
        lockOwner(userId);
        parent(userId,null,request.parentId());
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setUserId(userId);
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setStatus("ACTIVE");
        entity.setParentId(request.parentId());
        entity.setTemplateKey(request.templateKey()==null?"blank":request.templateKey());
        entity.setColor(request.color()==null?"#a78bfa":request.color());
        knowledgeBaseMapper.insert(entity);
        return KnowledgeBaseResponse.from(entity);
    }

    public List<KnowledgeBaseResponse> list(Long userId) {
        return knowledgeBaseMapper.selectList(Wrappers.<KnowledgeBaseEntity>lambdaQuery()
                        .eq(KnowledgeBaseEntity::getUserId, userId)
                        .ne(KnowledgeBaseEntity::getStatus, "DELETED")
                        .orderByDesc(KnowledgeBaseEntity::getCreateTime))
                .stream()
                .map(KnowledgeBaseResponse::from)
                .toList();
    }

    public KnowledgeBaseResponse get(Long userId, Long id) {
        return KnowledgeBaseResponse.from(getOwnedActive(userId, id));
    }

    @Transactional
    public KnowledgeBaseResponse update(Long userId, Long id, KnowledgeBaseUpdateRequest request) {
        lockOwner(userId);
        KnowledgeBaseEntity entity=getOwnedActive(userId,id);
        parent(userId,id,request.parentId());
        // Check descendants too: moving an existing tree must respect the depth limit.
        java.util.Map<Long,Long> parents=new java.util.HashMap<>();
        for(var kb:list(userId)) parents.put(kb.id(),kb.parentId());
        parents.put(id,request.parentId());
        for(Long node:parents.keySet()) {
            java.util.Set<Long> seen=new java.util.HashSet<>();Long cursor=node;
            while(cursor!=null) {
                if(!seen.add(cursor)||seen.size()>12)throw new BusinessException(400,"知识库层级最多 12 层");
                cursor=parents.get(cursor);
            }
        }
        entity.setName(request.name());entity.setDescription(request.description());
        entity.setParentId(request.parentId());
        if(request.templateKey()!=null)entity.setTemplateKey(request.templateKey());
        if(request.color()!=null)entity.setColor(request.color());
        knowledgeBaseMapper.updateById(entity);
        // MyBatis omits null fields by default; explicitly support moving back to the root.
        jdbc.update("UPDATE knowledge_base SET parent_id=? WHERE id=? AND user_id=?",request.parentId(),id,userId);
        return KnowledgeBaseResponse.from(entity);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        lockOwner(userId);
        KnowledgeBaseEntity entity=getOwnedActive(userId,id);
        Long children=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_base WHERE parent_id=? AND user_id=? AND status='ACTIVE'",Long.class,id,userId);
        if(children!=null && children>0)throw new BusinessException(409,"请先移动或删除子知识库");
        entity.setStatus("DELETED");knowledgeBaseMapper.updateById(entity);
    }

    public KnowledgeBaseEntity getOwnedActive(Long userId, Long id) {
        KnowledgeBaseEntity entity = knowledgeBaseMapper.selectOne(Wrappers.<KnowledgeBaseEntity>lambdaQuery()
                .eq(KnowledgeBaseEntity::getId, id)
                .eq(KnowledgeBaseEntity::getUserId, userId)
                .eq(KnowledgeBaseEntity::getStatus, "ACTIVE"));
        if (entity == null) {
            throw new BusinessException("知识库不存在或无权限访问");
        }
        return entity;
    }
}
