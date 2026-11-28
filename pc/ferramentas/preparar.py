"""Prepara o PC-Toolkit.ps1 para rodar dentro da janela do SerpyraToolkit para PC.

- tira o bloco param() e a parte final que abre o menu de texto (o programa chama cada funcao direto);
- troca a leitura de teclas do console ([Console]::KeyAvailable/ReadKey), que nao funciona com a
  entrada redirecionada, por funcoes que leem a tecla que o programa manda (botoes Parar, Manter...).

Uso: python preparar.py <PC-Toolkit.ps1> <saida.ps1>
"""
import re
import sys

origem, destino = sys.argv[1], sys.argv[2]
texto = open(origem, encoding="ascii").read().replace("\r\n", "\n")

# 1) parte final (menu de texto)
marca = "\n# EXECUCAO\n"
i = texto.find(marca)
if i < 0:
    sys.exit("Nao achei a secao EXECUCAO no script")
# volta ate a linha de ==== antes da marca
inicio = texto.rfind("\n# ====", 0, i)
texto = texto[: inicio if inicio > 0 else i] + "\n"

# 2) bloco param( ... ) do topo
m = re.search(r"^param\(\n.*?^\)\n", texto, flags=re.S | re.M)
if not m:
    sys.exit("Nao achei o bloco param()")
texto = texto[: m.start()] + texto[m.end():]

# 3) teclas
n1 = texto.count("[Console]::KeyAvailable")
n2 = texto.count("[Console]::ReadKey($true)")
texto = texto.replace("[Console]::KeyAvailable", "(Tecla-Disponivel)")
texto = texto.replace("[Console]::ReadKey($true)", "(Ler-Tecla)")
if n1 == 0 or n2 == 0:
    sys.exit("Nao achei a leitura de teclas para trocar")

open(destino, "w", encoding="ascii", newline="\r\n").write(texto)
print(f"ok: {destino} ({len(texto.splitlines())} linhas, {n1} teclas trocadas)")
