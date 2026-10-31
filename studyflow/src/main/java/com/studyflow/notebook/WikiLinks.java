package com.studyflow.notebook;

import org.springframework.stereotype.Component;
import com.studyflow.common.BusinessException;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Wiki links in prose; code blocks and inline code are deliberately ignored. */
@Component
public class WikiLinks {
    public record Token(String target,String label,String anchor) {}
    public List<Token> parse(String markdown) {
        StringBuilder prose=new StringBuilder();String fence=null;
        for(String line:markdown.split("\\n",-1)) {
            String trimmed=line.stripLeading();
            if(trimmed.startsWith("```")||trimmed.startsWith("~~~")) {
                String marker=trimmed.substring(0,3);
                if(fence==null)fence=marker;else if(fence.equals(marker))fence=null;
                prose.append('\n');continue;
            }
            prose.append(fence==null?line.replaceAll("`+[^`]*`+",""):"").append('\n');
        }
        var matcher=Pattern.compile("(?<!\\\\)\\[\\[([^\\]\\n]{1,480})]]").matcher(prose);
        List<Token> tokens=new ArrayList<>();
        while(matcher.find()) {
            String[] alias=matcher.group(1).split("\\|",2);String[] target=alias[0].split("#",2);
            String name=target[0].trim();if(name.isEmpty())continue;
            String label=(alias.length>1?alias[1]:name).trim();String anchor=target.length>1?target[1].trim():"";
            if(name.length()>240||label.length()>240||anchor.length()>160)throw new BusinessException(400,"双向链接过长");
            tokens.add(new Token(name,label,anchor));
            if(tokens.size()>300)throw new BusinessException(400,"单篇笔记最多 300 个链接");
        }return List.copyOf(tokens);
    }
    public static String key(String title) {return Normalizer.normalize(title.strip(),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
}
