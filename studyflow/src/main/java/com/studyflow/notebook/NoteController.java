package com.studyflow.notebook;

import cn.dev33.satoken.stp.StpUtil;
import com.studyflow.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.studyflow.notebook.NoteTypes.*;

@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
public class NoteController {
    private final NoteService notes;
    @PostMapping public Result<Note> create(@Valid @RequestBody Write request){return Result.ok(notes.save(StpUtil.getLoginIdAsLong(),null,request));}
    @PutMapping("/{id}") public Result<Note> update(@PathVariable Long id,@Valid @RequestBody Write request){return Result.ok(notes.save(StpUtil.getLoginIdAsLong(),id,request));}
    @GetMapping("/{id}") public Result<Note> get(@PathVariable Long id){return Result.ok(notes.get(StpUtil.getLoginIdAsLong(),id));}
    @DeleteMapping("/{id}") public Result<Void> delete(@PathVariable Long id,@RequestParam int revision){notes.delete(StpUtil.getLoginIdAsLong(),id,revision);return Result.ok();}
    @GetMapping public Result<Page> list(@RequestParam List<Long> knowledgeBaseIds,@RequestParam(required=false) Long before,
                                         @RequestParam(defaultValue="50") int size,@RequestParam(required=false) String query){
        return Result.ok(notes.list(StpUtil.getLoginIdAsLong(),knowledgeBaseIds,before,size,query));
    }
    @GetMapping("/{id}/backlinks") public Result<List<Summary>> backlinks(@PathVariable Long id,@RequestParam List<Long> knowledgeBaseIds){return Result.ok(notes.backlinks(StpUtil.getLoginIdAsLong(),id,knowledgeBaseIds));}
    @GetMapping("/graph") public Result<Graph> graph(@RequestParam List<Long> knowledgeBaseIds,@RequestParam(required=false) Long focus){return Result.ok(notes.graph(StpUtil.getLoginIdAsLong(),knowledgeBaseIds,focus));}
    public record Move(@jakarta.validation.constraints.NotNull Long knowledgeBaseId,@jakarta.validation.constraints.Min(1) int revision) {}
    @PostMapping("/{id}/move") public Result<Note> move(@PathVariable Long id,@Valid @RequestBody Move request){return Result.ok(notes.move(StpUtil.getLoginIdAsLong(),id,request.knowledgeBaseId(),request.revision()));}
    @GetMapping("/{id}/revisions") public Result<List<Revision>> revisions(@PathVariable Long id){return Result.ok(notes.revisions(StpUtil.getLoginIdAsLong(),id));}
    @GetMapping("/{id}/revisions/{version}") public Result<Note> revision(@PathVariable Long id,@PathVariable int version){return Result.ok(notes.revision(StpUtil.getLoginIdAsLong(),id,version));}
}
