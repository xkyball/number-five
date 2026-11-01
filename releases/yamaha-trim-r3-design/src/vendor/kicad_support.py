import json,re,uuid
NS=uuid.UUID("c170d428-e988-415e-91ef-2f83a1495b26")
def uid(key):
    return str(uuid.uuid5(NS, key))

def q(value):
    return json.dumps(str(value), ensure_ascii=False)

def balanced(text, start):
    depth = 0
    quoted = escaped = False
    for i in range(start, len(text)):
        c = text[i]
        if quoted:
            if escaped:
                escaped = False
            elif c == '\\':
                escaped = True
            elif c == '"':
                quoted = False
        elif c == '"':
            quoted = True
        elif c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    raise ValueError('Unbalanced library expression')

def pins(symbol):
    result = []
    for m in re.finditer(r'\(pin\s+\w+\s+\w+\s+\(at\s+', symbol):
        block = balanced(symbol, m.start())
        at = re.search(r'\(at\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\)', block)
        number = re.search(r'\(number\s+"([^"\n]+)"', block)
        name = re.search(r'\(name\s+"([^"\n]*)"', block)
        result.append((number[1], float(at[1]), float(at[2]), float(at[3]), name[1] if name else ''))
    return result

def fmt(value):
    return f'{value:.4f}'.rstrip('0').rstrip('.')

def effect(size=1.0, justify=None):
    return f'(effects (font (size {size} {size}))' + (f' (justify {justify})' if justify else '') + ')'

def prop(name, value, x, y, hidden=False):
    return f'(property {q(name)} {q(value)} (at {fmt(x)} {fmt(y)} 0) ' + ('(hide yes) ' if hidden else '') + effect() + ')'
