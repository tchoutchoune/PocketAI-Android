"""Integration check against the pinned upstream Qwen3 Jinja template, without model weights."""
import os
import pathlib
import subprocess
import jinja2

upstream = pathlib.Path(os.environ["POCKETAI_LLAMA_DIR"])
original = (upstream / "models/templates/Qwen-Qwen3-0.6B.jinja").read_text()
adapted = subprocess.run(["build/native-tests/inference-helpers", "adapt-template"],
                         input=original, text=True, capture_output=True, check=True).stdout
assert adapted != original
env = jinja2.Environment()
stock, stable = env.from_string(original), env.from_string(adapted)

def render(template, messages, thinking=False):
    return template.render(messages=messages, tools=[], add_generation_prompt=True, enable_thinking=thinking)

messages = [{"role": "system", "content": "Réponds en français."}, {"role": "user", "content": "Bonjour"}]
assert render(stock, messages) == render(stable, messages)
for answer, question in [("Bonjour !", "Une explication ?"), ("Du français et un emoji 🐱.", "Et du code ?"), ("```python\nprint('oui')\n```", "Merci")]:
    cached = render(stable, messages) + answer + "<|im_end|>\n"
    messages += [{"role": "assistant", "content": answer, "reasoning_content": "\n"}, {"role": "user", "content": question}]
    assert render(stable, messages).startswith(cached), "Assistant answer must retain an exact rendered prefix"
    assert not render(stock, messages).startswith(cached), "The stock template should expose the regression"
    assert render(stock, messages, True) == render(stable, messages, True), "Enabled-thinking behavior must remain unchanged"
    undefined = dict(messages=messages, tools=[], add_generation_prompt=True)
    assert stock.render(**undefined) == stable.render(**undefined)
print("Pinned Qwen3 template: stable disabled-thinking KV prefix across 3 turns; enabled/undefined behavior unchanged.")
