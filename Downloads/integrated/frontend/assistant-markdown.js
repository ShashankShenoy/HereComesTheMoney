// The model response is untrusted text. Escape it before adding our small, fixed set of tags.
const escapeHtml=value=>String(value??'').replace(/[&<>"']/g,char=>({
  '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
}[char]));

function inline(value){
  return escapeHtml(value)
    .replace(/`([^`\n]+)`/g,'<code>$1</code>')
    .replace(/\*\*([^*\n]+)\*\*/g,'<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g,'$1<em>$2</em>');
}

const cells=line=>line.trim().replace(/^\|/,'').replace(/\|$/,'').split('|').map(cell=>cell.trim());
const tableDivider=line=>/^\s*\|/.test(line)&&cells(line).length>1&&cells(line).every(cell=>/^:?-{3,}:?$/.test(cell));
const listItem=line=>/^\s*(?:[-*]|\d+\.)\s+(.+)$/.exec(line);
const heading=line=>/^\s*#{1,3}\s+(.+)$/.exec(line);
const tableStart=(lines,index)=>/^\s*\|/.test(lines[index]||'')&&tableDivider(lines[index+1]||'')&&cells(lines[index]).length===cells(lines[index+1]).length;

export function renderAssistantMarkdown(value){
  const lines=String(value??'').replace(/\r\n?/g,'\n').split('\n');
  const output=[];
  for(let i=0;i<lines.length;){
    if(!lines[i].trim()){i++;continue;}
    if(tableStart(lines,i)){
      const headers=cells(lines[i]);i+=2;
      const rows=[];
      while(i<lines.length&&/^\s*\|/.test(lines[i])&&cells(lines[i]).length===headers.length){rows.push(cells(lines[i++]));}
      output.push(`<div class="assistant-table-wrap"><table><thead><tr>${headers.map(cell=>`<th>${inline(cell)}</th>`).join('')}</tr></thead><tbody>${rows.map(row=>`<tr>${row.map(cell=>`<td>${inline(cell)}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`);
      continue;
    }
    const title=heading(lines[i]);
    if(title){output.push(`<h4>${inline(title[1])}</h4>`);i++;continue;}
    const item=listItem(lines[i]);
    if(item){
      const ordered=/^\s*\d+\./.test(lines[i]);const items=[];
      while(i<lines.length&&listItem(lines[i])&&/^\s*\d+\./.test(lines[i])===ordered){items.push(listItem(lines[i++])[1]);}
      const tag=ordered?'ol':'ul';output.push(`<${tag}>${items.map(text=>`<li>${inline(text)}</li>`).join('')}</${tag}>`);continue;
    }
    const paragraph=[];
    while(i<lines.length&&lines[i].trim()&&!tableStart(lines,i)&&!heading(lines[i])&&!listItem(lines[i]))paragraph.push(lines[i++].trim());
    if(paragraph.length)output.push(`<p>${inline(paragraph.join(' '))}</p>`);
    else i++;
  }
  return output.join('');
}
