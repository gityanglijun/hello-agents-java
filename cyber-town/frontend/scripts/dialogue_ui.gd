# ============================================================
# 对话 UI 控制器
# ============================================================
# 【核心知识点】
#
# 1. CanvasLayer vs Node2D
#    继承 CanvasLayer（不是 Node2D）：UI 在独立的渲染层，不受摄像机移动影响。
#    如果你的对话面板跟着角色走，那才需要用 Node2D。
#
# 2. Godot UI 节点树
#    CanvasLayer
#      └─ Panel (背景框)
#           ├─ Label (NPCName)        — 静态文本
#           ├─ Label (NPCTitle)       — 灰色职位文本
#           ├─ RichTextLabel (DialogueText) — 支持 BBCode 的富文本
#           ├─ LineEdit (PlayerInput)  — 单行输入框
#           ├─ Button (SendButton)     — 发送按钮
#           └─ Button (CloseButton)    — 关闭按钮
#
# 3. 输入拦截 (_input)
#    Godot 的输入是自底向上传播的。对话框打开时，必须在 _input 中拦截
#    WASD/E/空格，调用 get_viewport().set_input_as_handled() 阻止事件
#    继续向下传播到玩家/游戏层。这就实现了"打字时不会触发移动"。
#
# 4. 富文本 BBCode
#    RichTextLabel 支持类似 HTML 的标签：
#      [color=cyan]玩家[/color]        → 青色
#      [color=yellow]NPC名字[/color]    → 黄色
#      [color=gray]系统提示[/color]     → 灰色
#      [color=red]错误[/color]          → 红色
#    用 append_text() 逐行追加，scroll_to_line() 自动滚到底部。
#
# 5. 双向交互锁
#    对话开始 → player.set_interacting(true) → 玩家不能移动
#    对话开始 → npc.set_interacting(true)    → NPC 停止巡逻
#    对话结束 → 两者都恢复。这是通过 call_group / set_interacting
#    方法在 player/npc/dialogue_ui 三者之间协调的。
# ============================================================
extends CanvasLayer

# ==================== 节点引用 ====================
# @onready: 在 _ready() 执行前自动完成 get_node()，比在 _ready 里手动写更简洁
@onready var panel: Panel = $Panel
@onready var npc_name_label: Label = $Panel/NPCName
@onready var npc_title_label: Label = $Panel/NPCTitle
@onready var dialogue_text: RichTextLabel = $Panel/DialogueText
@onready var player_input: LineEdit = $Panel/PlayerInput
@onready var send_button: Button = $Panel/SendButton
@onready var close_button: Button = $Panel/CloseButton

var current_npc_name: String = ""
var api_client: Node = null

func _ready():
	add_to_group("dialogue_system")
	visible = false

	# 按钮的 pressed 信号在鼠标点击或触摸时发射
	send_button.pressed.connect(_on_send_button_pressed)
	close_button.pressed.connect(_on_close_button_pressed)
	# LineEdit 的 text_submitted 在用户按回车时发射（携带当前文本）
	player_input.text_submitted.connect(_on_text_submitted)

	# autoload 可通过 /root/NodeName 全局访问
	api_client = get_node_or_null("/root/APIClient")
	if api_client:
		api_client.chat_response_received.connect(_on_chat_response_received)
		api_client.chat_error.connect(_on_chat_error)

	print("[INFO] 对话UI初始化完成")

# ==================== 键盘输入拦截 ====================

func _input(event: InputEvent):
	# 对话框隐藏时不干预任何输入
	if not visible:
		return

	if event is InputEventKey and event.pressed and not event.echo:
		# ESC 关闭对话框
		if event.keycode == KEY_ESCAPE:
			hide_dialogue()
			get_viewport().set_input_as_handled()
			return

		# 回车发送（只在输入框没有焦点时才手动发，有焦点时 LineEdit 自己处理）
		if event.keycode == KEY_ENTER or event.keycode == KEY_KP_ENTER:
			if not player_input.has_focus():
				send_message()
				get_viewport().set_input_as_handled()
			return

		# 屏蔽游戏操作键，防止穿透触发移动/交互
		if event.keycode in [KEY_E, KEY_SPACE, KEY_W, KEY_A, KEY_S, KEY_D]:
			get_viewport().set_input_as_handled()

# ==================== 对话生命周期 ====================

func start_dialogue(npc_name: String):
	"""由 main.gd 通过 call_group("dialogue_system", "start_dialogue", name) 调用"""
	current_npc_name = npc_name

	# 锁定 NPC（停止巡逻）
	var npc = get_npc_by_name(npc_name)
	if npc and npc.has_method("set_interacting"):
		npc.set_interacting(true)

	npc_name_label.text = npc_name
	npc_title_label.text = Config.NPC_TITLES.get(npc_name, "")

	dialogue_text.clear()
	dialogue_text.append_text("[color=gray]与 " + npc_name + " 的对话开始...[/color]\n")
	player_input.text = ""

	show_dialogue()
	player_input.grab_focus()

func show_dialogue():
	visible = true
	var player = get_tree().get_first_node_in_group("player")
	if player and player.has_method("set_interacting"):
		player.set_interacting(true)

func hide_dialogue():
	visible = false

	# 释放 NPC
	if current_npc_name != "":
		var npc = get_npc_by_name(current_npc_name)
		if npc and npc.has_method("set_interacting"):
			npc.set_interacting(false)

	current_npc_name = ""

	# 释放玩家
	var player = get_tree().get_first_node_in_group("player")
	if player and player.has_method("set_interacting"):
		player.set_interacting(false)

# ==================== 消息流 ====================

func _on_send_button_pressed():
	send_message()

func _on_text_submitted(_text: String):
	send_message()

func send_message():
	"""核心发送流程：校验 → 显示 → 请求"""
	var message = player_input.text.strip_edges()
	if message.is_empty() or current_npc_name.is_empty():
		return

	# 显示玩家消息（青色）
	dialogue_text.append_text("\n[color=cyan]玩家:[/color] " + message + "\n")
	player_input.text = ""
	dialogue_text.append_text("[color=gray]等待回复...[/color]\n")

	if api_client:
		api_client.send_chat(current_npc_name, message)
	else:
		print("[ERROR] API客户端未找到")

# ==================== API 响应 → UI 更新 ====================

func _on_chat_response_received(npc_name: String, message: String):
	if npc_name != current_npc_name:
		return

	# 移除"等待回复..." — 重建文本内容
	var text = dialogue_text.get_parsed_text()
	dialogue_text.clear()
	var lines = text.split("\n")
	for i in range(lines.size() - 2):  # 跳过最后两行（等待提示 + 空行）
		if lines[i] != "":
			dialogue_text.append_text(lines[i] + "\n")

	# 显示 NPC 回复（黄色）并自动滚到底部
	dialogue_text.append_text("[color=yellow]" + npc_name + ":[/color] " + message + "\n")
	dialogue_text.scroll_to_line(dialogue_text.get_line_count() - 1)

func _on_chat_error(error_message: String):
	dialogue_text.append_text("[color=red]错误: " + error_message + "[/color]\n")

func _on_close_button_pressed():
	hide_dialogue()

# ==================== 辅助 ====================

func get_npc_by_name(npc_name: String) -> Node:
	"""通过 npc_name 属性从 npcs 组中找到对应节点"""
	for npc in get_tree().get_nodes_in_group("npcs"):
		if npc.npc_name == npc_name:
			return npc
	return null
