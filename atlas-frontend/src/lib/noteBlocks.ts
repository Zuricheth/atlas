const BLOCK_NAMES = ['ATLAS_HUMAN_NOTE', 'VCP_AI_MEMORY'] as const

export function parseAgentNoteContent(content = '') {
  const humanBlocks = extractAllDelimitedBlocks(content, 'ATLAS_HUMAN_NOTE')
  const memoryBlocks = extractAllDelimitedBlocks(content, 'VCP_AI_MEMORY')
  const rawBody = stripDelimitedBlocks(content).trim()
  const body = stripAgentProtocolArtifacts(rawBody)
  if (humanBlocks.length === 0 && memoryBlocks.length === 0) {
    return {
      hasDualBlocks: false,
      body,
      human: body,
      memory: '',
      humanBlocks: [],
      memoryBlocks: [],
    }
  }

  return {
    hasDualBlocks: true,
    body,
    human: humanBlocks.filter(Boolean).join('\n\n'),
    memory: memoryBlocks.filter(Boolean).join('\n\n---\n\n'),
    humanBlocks,
    memoryBlocks,
  }
}

export function replaceDelimitedBlock(text: string, name: string, value: string) {
  const normalized = value.trim()
  const block = wrapDelimitedBlock(name, normalized)
  const pattern = new RegExp(`<<<\\s*${name}\\s*>>>[\\s\\S]*?<<<\\s*END_${name}\\s*>>>`, 'i')
  if (pattern.test(text)) return text.replace(pattern, block)
  const trimmed = text.trim()
  return trimmed ? `${trimmed}\n\n${block}` : block
}

export function wrapDelimitedBlock(name: string, value: string) {
  return `<<<${name}>>>\n${value.trim()}\n<<<END_${name}>>>`
}

function extractAllDelimitedBlocks(text: string, name: string) {
  const pattern = new RegExp(`<<<\\s*${name}\\s*>>>([\\s\\S]*?)<<<\\s*END_${name}\\s*>>>`, 'gi')
  const blocks: string[] = []
  let match: RegExpExecArray | null
  while ((match = pattern.exec(text)) !== null) {
    if (match[1]?.trim()) blocks.push(match[1].trim())
  }
  return blocks
}

function stripDelimitedBlocks(text: string) {
  return BLOCK_NAMES.reduce((current, name) => (
    current.replace(new RegExp(`<<<\\s*${name}\\s*>>>[\\s\\S]*?<<<\\s*END_${name}\\s*>>>`, 'gi'), '')
  ), text)
}

function stripAgentProtocolArtifacts(text: string) {
  return stripLooseMemoryTail(text)
    .replace(/<<<\s*ATLAS_HUMAN_NOTE\s*>>>/gi, '')
    .replace(/<<<\s*END_ATLAS_HUMAN_NOTE\s*>>>/gi, '')
    .replace(/<<<\s*VCP_AI_MEMORY\s*>>>[\s\S]*$/gi, '')
    .replace(/<<<\s*END_VCP_AI_MEMORY\s*>>>/gi, '')
    .trim()
}

function stripLooseMemoryTail(text: string) {
  const markers = [
    '【同步建议】',
    '【核心概念】',
    '【简明释义】',
    '【关键原理',
    '【应用场景】',
    '【关联节点】',
    '【联想锚定】',
    '【反思与洞察】',
    '【信源出处】',
  ]
  const looseEnd = text.search(/<<<\s*END_VCP_AI_MEMORY\s*>>>/i)
  if (looseEnd >= 0) {
    const beforeEnd = text.slice(0, looseEnd)
    const starts = markers
      .map((marker) => beforeEnd.lastIndexOf(marker))
      .filter((index) => index >= 0)
      .sort((a, b) => b - a)
    if (starts.length && /tag\s*[:：]/i.test(beforeEnd.slice(starts[0]))) {
      return text.slice(0, starts[0]).trim()
    }
    const tagLine = beforeEnd.search(/(?:^|\n)\s*tag\s*[:：]/i)
    if (tagLine >= 0) {
      return beforeEnd.slice(0, tagLine).trim()
    }
  }

  const lower = text.toLowerCase()
  if (!lower.includes('tag:')) return text
  const starts = markers
    .map((marker) => text.indexOf(marker))
    .filter((index) => index >= 0)
    .sort((a, b) => a - b)
  if (!starts.length) return text
  const start = starts[0]
  const tail = text.slice(start)
  if (!/tag\s*[:：]/i.test(tail)) return text
  return text.slice(0, start).trim()
}
